package com.mundymorning.couchcast.cast

import com.mundymorning.couchcast.play.PlayOutcome
import com.mundymorning.couchcast.play.PlayResult
import com.mundymorning.couchcast.play.PlayerInfo
import kotlinx.coroutines.delay

enum class CastPlayerState { UNKNOWN, IDLE, LOADING, BUFFERING, PLAYING, PAUSED }

/** What the TV last reported. [error]: it gave up on the media. [finished]: it played to the end. */
data class CastSnapshot(val state: CastPlayerState, val error: Boolean = false, val finished: Boolean = false) {
    fun toPlayerInfo() = PlayerInfo(
        state = when (state) {
            CastPlayerState.PLAYING -> "play"
            CastPlayerState.BUFFERING, CastPlayerState.LOADING -> "buffer"
            CastPlayerState.PAUSED -> "pause"
            else -> "close"
        },
        error = error,
    )
}

/** The part of Google Cast the confirmation logic needs, so that logic can be tested without Play Services. */
interface CastTransport {
    fun isConnected(): Boolean
    /** True only when the TV accepted the load request. */
    suspend fun load(url: String, contentType: String): Boolean
    suspend fun snapshot(): CastSnapshot?
    suspend fun stop(): Boolean
}

object CastFormats {
    /** MIME type the Cast media receiver needs for a format name used by the Play flow (hls / dash / mp4 / mkv). */
    fun contentType(format: String): String = when (format.lowercase()) {
        "hls" -> "application/x-mpegURL"
        "dash" -> "application/dash+xml"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }
}

/**
 * Same promise as the Roku path: report PLAYING only when the TV itself says it is playing and keeps saying it for
 * [stableMs]. A stream that starts and then dies is STOPPED_EARLY. Never guesses.
 */
class CastPlayback(
    private val transport: CastTransport,
    private val waitMs: Long = 20_000,
    private val stableMs: Long = 6_000,
    private val pollMs: Long = 1_000,
) {
    suspend fun play(url: String, format: String): PlayOutcome {
        if (!transport.isConnected()) return PlayOutcome(PlayResult.SEND_FAILED)
        if (!transport.load(url, CastFormats.contentType(format))) return PlayOutcome(PlayResult.SEND_FAILED)

        val end = System.currentTimeMillis() + waitMs
        var last: CastSnapshot? = null
        var playingSince = 0L
        while (System.currentTimeMillis() < end) {
            val s = transport.snapshot()
            if (s != null) last = s
            if (s != null && s.error) return PlayOutcome(PlayResult.STOPPED_EARLY, s.toPlayerInfo(), 0)
            if (s != null && s.state == CastPlayerState.PLAYING) { playingSince = System.currentTimeMillis(); break }
            delay(pollMs)
        }
        if (playingSince == 0L) {
            return PlayOutcome(if (last == null) PlayResult.NOT_STARTED else PlayResult.RECEIVER_OPEN_NOT_CONFIRMED, last?.toPlayerInfo())
        }
        while (System.currentTimeMillis() - playingSince < stableMs) {
            delay(pollMs)
            val s = transport.snapshot() ?: continue
            val alive = !s.error && (s.state == CastPlayerState.PLAYING || s.state == CastPlayerState.BUFFERING ||
                s.state == CastPlayerState.PAUSED || s.finished)
            if (alive) { last = s; continue }
            val secs = ((System.currentTimeMillis() - playingSince) / 1000).toInt()
            return PlayOutcome(PlayResult.STOPPED_EARLY, (last ?: s).toPlayerInfo(), secs)
        }
        return PlayOutcome(PlayResult.PLAYING, last?.toPlayerInfo())
    }
}

/** Plain-English results for the Cast screen. Honest about what Cast can and cannot do. */
object CastMessages {
    fun forOutcome(out: PlayOutcome): String = when (out.result) {
        PlayResult.PLAYING -> "Confirmed: the TV reports it is playing."
        PlayResult.STOPPED_EARLY ->
            if (out.playedSeconds == 0) "The TV could not play this video. $HINT"
            else "The video started, then the TV stopped it after about ${out.playedSeconds} seconds. $HINT"
        PlayResult.RECEIVER_OPEN_NOT_CONFIRMED ->
            "The TV accepted the video but has not reported playing. It may still be loading, or the video may be a format the TV can't play."
        PlayResult.NOT_STARTED -> "The TV did not respond. Make sure it is on, and reconnect with the Cast icon."
        PlayResult.SEND_FAILED -> "The TV is not connected. Tap the Cast icon and choose your TV first."
    }

    const val HINT = "Cast TVs fetch the video themselves, so sites that need a sign-in, a browser header, or don't allow other " +
        "apps to read their streams won't work here yet. Direct MP4 links work best."
}

object CastLinks {
    /** http(s) links only; adds https:// to a bare host. Null when it can't be a web link. */
    fun normalize(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val u = if (Regex("^(?i)https?://.+").matches(t)) t else if ("://" in t) return null else "https://$t"
        return if (Regex("^(?i)https?://[^/\\s]+.*").matches(u)) u else null
    }
}
