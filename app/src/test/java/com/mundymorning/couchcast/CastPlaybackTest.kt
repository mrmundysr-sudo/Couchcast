package com.mundymorning.couchcast

import com.mundymorning.couchcast.cast.CastFormats
import com.mundymorning.couchcast.cast.CastLinks
import com.mundymorning.couchcast.cast.CastMessages
import com.mundymorning.couchcast.cast.CastPlayback
import com.mundymorning.couchcast.cast.CastPlayerState
import com.mundymorning.couchcast.cast.CastSnapshot
import com.mundymorning.couchcast.cast.CastTransport
import com.mundymorning.couchcast.cast.GoogleCastReceiver
import com.mundymorning.couchcast.play.PlayOutcome
import com.mundymorning.couchcast.play.PlayResult
import com.mundymorning.couchcast.playback.ReceiverPlatform
import com.mundymorning.couchcast.receiver.PlayRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted TV, not a real Cast device. Each snapshot() call returns the next entry; the last one repeats. */
private class FakeTv(
    private val script: List<CastSnapshot?>,
    var connected: Boolean = true,
    var accepts: Boolean = true,
) : CastTransport {
    var loaded: Pair<String, String>? = null
    var stopped = false
    private var i = 0
    override fun isConnected() = connected
    override suspend fun load(url: String, contentType: String): Boolean { loaded = url to contentType; return accepts }
    override suspend fun snapshot(): CastSnapshot? = script.getOrNull(minOf(i++, script.size - 1))
    override suspend fun stop(): Boolean { stopped = true; return connected }
}

private fun snap(s: CastPlayerState, error: Boolean = false, finished: Boolean = false) = CastSnapshot(s, error, finished)
private fun playback(tv: CastTransport) = CastPlayback(tv, waitMs = 1500, stableMs = 100, pollMs = 10)

class CastPlaybackTest {
    @Test fun confirmsPlayingOnlyWhenTheTvSaysPlayingAndStaysUp() = runBlocking {
        val tv = FakeTv(listOf(snap(CastPlayerState.IDLE), snap(CastPlayerState.LOADING), snap(CastPlayerState.BUFFERING), snap(CastPlayerState.PLAYING)))
        val out = playback(tv).play("https://x.com/v.mp4", "mp4")
        assertEquals(PlayResult.PLAYING, out.result)
        assertEquals("play", out.last!!.state)
        assertEquals("https://x.com/v.mp4" to "video/mp4", tv.loaded)
    }

    @Test fun neverClaimsPlayingWhileTheTvOnlyBuffers() = runBlocking {
        val out = playback(FakeTv(listOf(snap(CastPlayerState.BUFFERING)))).play("https://x.com/v.mp4", "mp4")
        assertEquals(PlayResult.RECEIVER_OPEN_NOT_CONFIRMED, out.result)
    }

    @Test fun anErrorBeforePlayingIsReportedAsFailedNotPlaying() = runBlocking {
        val out = playback(FakeTv(listOf(snap(CastPlayerState.LOADING), snap(CastPlayerState.IDLE, error = true)))).play("https://x.com/v.m3u8", "hls")
        assertEquals(PlayResult.STOPPED_EARLY, out.result)
        assertEquals(0, out.playedSeconds)
        assertTrue(out.last!!.error)
    }

    @Test fun aStreamThatStartsThenDiesIsStoppedEarly() = runBlocking {
        val out = playback(FakeTv(listOf(snap(CastPlayerState.PLAYING), snap(CastPlayerState.IDLE)))).play("https://x.com/v.mp4", "mp4")
        assertEquals(PlayResult.STOPPED_EARLY, out.result)
    }

    @Test fun aShortVideoThatFinishesIsStillPlayed() = runBlocking {
        val out = playback(FakeTv(listOf(snap(CastPlayerState.PLAYING), snap(CastPlayerState.IDLE, finished = true)))).play("https://x.com/v.mp4", "mp4")
        assertEquals(PlayResult.PLAYING, out.result)
    }

    @Test fun pausedAndBufferingDuringTheStableWindowAreFine() = runBlocking {
        val out = playback(FakeTv(listOf(snap(CastPlayerState.PLAYING), snap(CastPlayerState.BUFFERING), snap(CastPlayerState.PAUSED)))).play("https://x.com/v.mp4", "mp4")
        assertEquals(PlayResult.PLAYING, out.result)
    }

    @Test fun silentTvIsNotStarted() = runBlocking {
        assertEquals(PlayResult.NOT_STARTED, playback(FakeTv(listOf(null))).play("https://x.com/v.mp4", "mp4").result)
    }

    @Test fun notConnectedOrRejectedLoadIsSendFailed() = runBlocking {
        assertEquals(PlayResult.SEND_FAILED, playback(FakeTv(listOf(snap(CastPlayerState.PLAYING)), connected = false)).play("https://x.com/v.mp4", "mp4").result)
        val rejecting = FakeTv(listOf(snap(CastPlayerState.PLAYING)), accepts = false)
        assertEquals(PlayResult.SEND_FAILED, playback(rejecting).play("https://x.com/v.mp4", "mp4").result)
    }

    @Test fun mapsFormatsToCastContentTypes() {
        assertEquals("application/x-mpegURL", CastFormats.contentType("hls"))
        assertEquals("application/dash+xml", CastFormats.contentType("dash"))
        assertEquals("video/x-matroska", CastFormats.contentType("mkv"))
        assertEquals("video/mp4", CastFormats.contentType("mp4"))
        assertEquals("video/mp4", CastFormats.contentType("anything-else"))
    }

    @Test fun receiverAdapterReportsCastAndStopsTheTv() = runBlocking {
        val tv = FakeTv(listOf(snap(CastPlayerState.PLAYING)))
        val r = GoogleCastReceiver(tv, 1500, 100, 10)
        assertEquals(ReceiverPlatform.GOOGLE_CAST, r.platform)
        // Referer and User-Agent are dropped: the Cast media receiver cannot send them.
        assertEquals(PlayResult.PLAYING, r.play("ignored", PlayRequest("https://x.com/v.m3u8", "hls", "https://site.com", "UA")).result)
        assertEquals("https://x.com/v.m3u8" to "application/x-mpegURL", tv.loaded)
        assertEquals("play", r.playerInfo("ignored")!!.state)
        assertTrue(r.stop("ignored")); assertTrue(tv.stopped)
    }

    @Test fun messagesNeverSayPlayingUnlessConfirmed() {
        for (res in PlayResult.values()) {
            val m = CastMessages.forOutcome(PlayOutcome(res, null, 3))
            assertEquals(res.toString(), res == PlayResult.PLAYING, m.startsWith("Confirmed"))
        }
        assertTrue(CastMessages.forOutcome(PlayOutcome(PlayResult.STOPPED_EARLY, null, 0)).contains("could not play"))
        assertTrue(CastMessages.forOutcome(PlayOutcome(PlayResult.STOPPED_EARLY, null, 7)).contains("7 seconds"))
    }

    @Test fun normalizesLinks() {
        assertEquals("https://x.com/a", CastLinks.normalize(" x.com/a "))
        assertEquals("http://x.com/v.mp4", CastLinks.normalize("http://x.com/v.mp4"))
        assertNull(CastLinks.normalize("javascript:alert(1)"))
        assertNull(CastLinks.normalize("ftp://x.com/v.mp4"))
        assertNull(CastLinks.normalize("two words"))
        assertNull(CastLinks.normalize(""))
        assertFalse(CastLinks.normalize("file:///sdcard/a.mp4") != null)
    }
}
