package com.mundymorning.couchcast.play

import com.mundymorning.couchcast.install.InstallPlanner
import com.mundymorning.couchcast.install.RokuDevClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class PlayerInfo(
    val state: String, val error: Boolean,
    val video: String? = null, val audio: String? = null, val drm: String? = null, val resolution: String? = null,
)

enum class PlayResult { PLAYING, STOPPED_EARLY, RECEIVER_OPEN_NOT_CONFIRMED, NOT_STARTED, SEND_FAILED }

/** [last] is the Roku's last report of what it was playing; [playedSeconds] is how long it stayed up. */
data class PlayOutcome(val result: PlayResult, val last: PlayerInfo? = null, val playedSeconds: Int = 0)

/** Sends a video link to the Couchcast Receiver (ECP launch / input deep link) and checks the Roku's own player state. */
class ReceiverPlayer(
    private val dev: RokuDevClient = RokuDevClient(),
    private val ecpPort: Int = 8060,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .retryOnConnectionFailure(false).build()

    /**
     * Starts playback and only reports PLAYING when the Roku says "play" and keeps saying it for [stableMs].
     * A stream that starts and then dies is reported as STOPPED_EARLY with the Roku's own format report.
     */
    suspend fun play(ip: String, url: String, format: String, waitMs: Long = 20_000, stableMs: Long = 6_000): PlayOutcome {
        val running = dev.activeAppId(ip) == InstallPlanner.DEV_APP_ID
        if (!post(ip, requestPath(running, url, format))) return PlayOutcome(PlayResult.SEND_FAILED)
        val end = System.currentTimeMillis() + waitMs
        var last: PlayerInfo? = null
        var playingSince = 0L
        while (System.currentTimeMillis() < end) {
            val info = playerInfo(ip)
            if (info != null) last = info
            if (info != null && !info.error && info.state == "play") { playingSince = System.currentTimeMillis(); break }
            delay(1000)
        }
        if (playingSince == 0L) {
            val open = dev.activeAppId(ip) == InstallPlanner.DEV_APP_ID
            return PlayOutcome(if (open) PlayResult.RECEIVER_OPEN_NOT_CONFIRMED else PlayResult.NOT_STARTED, last)
        }
        while (System.currentTimeMillis() - playingSince < stableMs) {
            delay(1000)
            val info = playerInfo(ip) ?: continue
            if (info.state == "play" || info.state == "buffer" || info.state == "pause") {
                if (!info.error) { last = info; continue }
            }
            val secs = ((System.currentTimeMillis() - playingSince) / 1000).toInt()
            return PlayOutcome(PlayResult.STOPPED_EARLY, last ?: info, secs)
        }
        return PlayOutcome(PlayResult.PLAYING, last)
    }

    private suspend fun post(ip: String, path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("http://$ip:$ecpPort$path").post(ByteArray(0).toRequestBody(null)).build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }

    suspend fun playerInfo(ip: String): PlayerInfo? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("http://$ip:$ecpPort/query/media-player").get().build()).execute().use { r ->
                if (r.isSuccessful) parsePlayer(r.body?.string().orEmpty()) else null
            }
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        private val FORMAT = Regex("^[^?#]*\\.(m3u8|mpd|mp4|m4v|mov|mkv)(?:[?#]|$)", RegexOption.IGNORE_CASE)

        /** hls / dash / mp4 / mkv from the link, or null when it does not look like a direct video link. */
        fun formatFor(url: String): String? = FORMAT.find(url.trim())?.groupValues?.get(1)?.lowercase()?.let {
            when (it) { "m3u8" -> "hls"; "mpd" -> "dash"; "mkv" -> "mkv"; else -> "mp4" }
        }

        fun query(url: String, format: String): String {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
            return "contentId=${enc(url)}&mediaType=shortFormVideo&format=${enc(format)}"
        }

        /** Already running: deliver as an input event. Otherwise launch the dev channel with the link. */
        fun requestPath(running: Boolean, url: String, format: String): String =
            (if (running) "/input?" else "/launch/${InstallPlanner.DEV_APP_ID}?") + query(url, format)

        private fun attr(tag: String, name: String): String? =
            Regex("\\b$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

        fun parsePlayer(xml: String): PlayerInfo? {
            val tag = Regex("<player\\b[^>]*>").find(xml)?.value ?: return null
            val state = attr(tag, "state") ?: return null
            val fmt = Regex("<format\\b[^>]*>").find(xml)?.value
            return PlayerInfo(
                state, Regex("\\berror=\"true\"").containsMatchIn(tag),
                video = fmt?.let { attr(it, "video") }, audio = fmt?.let { attr(it, "audio") },
                drm = fmt?.let { attr(it, "drm") }, resolution = fmt?.let { attr(it, "video_res") },
            )
        }
    }
}
