package com.mundymorning.couchcast

import com.mundymorning.couchcast.install.RokuDevClient
import com.mundymorning.couchcast.play.PlayOutcome
import com.mundymorning.couchcast.play.PlayResult
import com.mundymorning.couchcast.play.ReceiverPlayer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ReceiverPlayerTest {
    @Test fun detectsDirectVideoFormats() {
        assertEquals("mp4", ReceiverPlayer.formatFor("https://x.com/a/b.MP4"))
        assertEquals("hls", ReceiverPlayer.formatFor("https://x.com/live/master.m3u8?token=abc"))
        assertEquals("dash", ReceiverPlayer.formatFor("http://x.com/v.mpd#t=1"))
        assertEquals("mkv", ReceiverPlayer.formatFor("https://x.com/v.mkv"))
        assertNull(ReceiverPlayer.formatFor("https://www.youtube.com/watch?v=abc"))
        assertNull(ReceiverPlayer.formatFor("https://x.com/page.html?file=a.mp4"))
    }

    @Test fun queryEncodesTheLinkSoItCannotInjectParameters() {
        val q = ReceiverPlayer.query("https://x.com/v.mp4?a=1&b=2 c", "mp4")
        assertTrue(q, q.startsWith("contentId=https%3A%2F%2Fx.com%2Fv.mp4%3Fa%3D1%26b%3D2%20c&"))
        assertEquals(1, q.split("&mediaType=").size - 1)
        assertTrue(q.endsWith("&mediaType=shortFormVideo&format=mp4"))
    }

    @Test fun choosesInputWhenRunningAndLaunchOtherwise() {
        assertTrue(ReceiverPlayer.requestPath(true, "https://x.com/v.mp4", "mp4").startsWith("/input?contentId="))
        assertTrue(ReceiverPlayer.requestPath(false, "https://x.com/v.mp4", "mp4").startsWith("/launch/dev?contentId="))
    }

    @Test fun parsesFormatAndDrm() {
        val i = ReceiverPlayer.parsePlayer("<player error=\"false\" state=\"play\"><format audio=\"aac\" drm=\"widevine\" video=\"hevc\" video_res=\"1920x1080\"/></player>")!!
        assertEquals("hevc", i.video); assertEquals("aac", i.audio); assertEquals("widevine", i.drm); assertEquals("1920x1080", i.resolution)
    }

    @Test fun parsesMediaPlayerState() {
        assertEquals("play", ReceiverPlayer.parsePlayer("<player error=\"false\" state=\"play\"><plugin id=\"dev\"/></player>")!!.state)
        assertTrue(ReceiverPlayer.parsePlayer("<player state=\"close\" error=\"true\"></player>")!!.error)
        assertNull(ReceiverPlayer.parsePlayer("<html>nope</html>"))
    }
}

class MediaLinksTest {
    @Test fun keepsOnlyDirectMediaLinksAndDedupes() {
        val out = com.mundymorning.couchcast.play.MediaLinks.clean(listOf(
            "https://cdn.x.com/a/master.m3u8?t=1", "https://cdn.x.com/a/master.m3u8?t=1#frag",
            "https://x.com/page.html", "blob:https://x.com/123", "javascript:void(0)",
            "http://x.com/v.MP4", "https://x.com/seg1.ts"))
        assertEquals(listOf("https://cdn.x.com/a/master.m3u8?t=1", "http://x.com/v.MP4"), out)
    }

    @Test fun prefersStreamingPlaylistsOverLooseMp4() {
        val out = com.mundymorning.couchcast.play.MediaLinks.clean(listOf(
            "https://x.com/ad.mp4", "https://x.com/main/master.m3u8?t=1", "https://x.com/preview.mp4", "https://x.com/v.mpd"))
        assertEquals(listOf("https://x.com/main/master.m3u8?t=1", "https://x.com/v.mpd", "https://x.com/ad.mp4", "https://x.com/preview.mp4"), out)
    }

    @Test fun labelsAreShort() {
        assertEquals("cdn.x.com / master.m3u8", com.mundymorning.couchcast.play.MediaLinks.label("https://cdn.x.com/a/b/master.m3u8?token=zzz"))
        assertTrue(com.mundymorning.couchcast.play.MediaLinks.label("https://x.com/" + "a".repeat(80) + ".mp4").length < 45)
    }
}

/** A fake Roku ECP endpoint. It is not a real Roku. */
class ReceiverPlayerFakeEcpTest {
    private lateinit var server: MockWebServer
    private val seen = CopyOnWriteArrayList<String>()
    @Volatile private var activeId = "tvinput.hdmi1"
    @Volatile private var playerState = "play"
    /** After this many media-player queries the fake Roku switches to [afterState] (simulates a stream that dies). */
    @Volatile private var dieAfter = Int.MAX_VALUE
    @Volatile private var afterState = "close"
    private val playerQueries = java.util.concurrent.atomic.AtomicInteger()

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += "${request.method} ${request.path}"
                return when {
                    request.path!!.startsWith("/query/active-app") ->
                        MockResponse().setBody("<active-app><app id=\"$activeId\">x</app></active-app>")
                    request.path!!.startsWith("/query/media-player") -> {
                        val st = if (playerQueries.incrementAndGet() > dieAfter) afterState else playerState
                        MockResponse().setBody("<player error=\"false\" state=\"$st\"><format audio=\"aac\" captions=\"none\" drm=\"none\" video=\"\" video_res=\"\"/></player>")
                    }
                    request.path!!.startsWith("/launch/") || request.path!!.startsWith("/input") -> MockResponse()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun player() = ReceiverPlayer(RokuDevClient(ecpPort = server.port), server.port)

    @Test fun launchesTheReceiverThenConfirmsPlayback() = runBlocking {
        val r = player().play("127.0.0.1", "https://x.com/v.mp4", "mp4", waitMs = 3000, stableMs = 1500)
        assertEquals(PlayResult.PLAYING, r.result)
        assertTrue(seen.toString(), seen.any { it.startsWith("POST /launch/dev?contentId=https%3A%2F%2Fx.com%2Fv.mp4") })
    }

    @Test fun usesInputWhenReceiverAlreadyRunning() = runBlocking {
        activeId = "dev"
        assertEquals(PlayResult.PLAYING, player().play("127.0.0.1", "https://x.com/v.mp4", "mp4", waitMs = 3000, stableMs = 1500).result)
        assertTrue(seen.toString(), seen.any { it.startsWith("POST /input?contentId=") })
        assertFalse(seen.any { it.startsWith("POST /launch/") })
    }

    @Test fun streamThatStartsThenDiesIsReportedWithTheRokusFormatReport() = runBlocking {
        activeId = "dev"; dieAfter = 2
        val out: PlayOutcome = player().play("127.0.0.1", "https://x.com/v.m3u8", "hls", waitMs = 3000, stableMs = 6000)
        assertEquals(PlayResult.STOPPED_EARLY, out.result)
        assertEquals("aac", out.last!!.audio)
        assertTrue(out.last!!.video.isNullOrBlank())
    }

    @Test fun bufferingAloneIsNotPlaying() = runBlocking {
        activeId = "dev"; playerState = "buffer"
        assertEquals(PlayResult.RECEIVER_OPEN_NOT_CONFIRMED, player().play("127.0.0.1", "https://x.com/v.mp4", "mp4", waitMs = 1500, stableMs = 1000).result)
    }

    @Test fun neverClaimsPlayingWhenTheRokuSaysItIsNot() = runBlocking {
        activeId = "dev"; playerState = "close"
        assertEquals(PlayResult.RECEIVER_OPEN_NOT_CONFIRMED, player().play("127.0.0.1", "https://x.com/v.mp4", "mp4", waitMs = 1500, stableMs = 1000).result)
    }

    @Test fun reportsNotStartedWhenReceiverNeverOpens() = runBlocking {
        playerState = "none"
        assertEquals(PlayResult.NOT_STARTED, player().play("127.0.0.1", "https://x.com/v.mp4", "mp4", waitMs = 1200, stableMs = 1000).result)
    }
}
