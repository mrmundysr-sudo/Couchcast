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

data class PlayerInfo(val state: String, val error: Boolean)

enum class PlayResult { PLAYING, RECEIVER_OPEN_NOT_CONFIRMED, NOT_STARTED, SEND_FAILED }

/** Sends a video link to the Couchcast Receiver (ECP launch / input deep link) and checks the Roku's own player state. */
class ReceiverPlayer(
    private val dev: RokuDevClient = RokuDevClient(),
    private val ecpPort: Int = 8060,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .retryOnConnectionFailure(false).build()

    /** Starts playback and only reports PLAYING when `/query/media-player` says so. */
    suspend fun play(ip: String, url: String, format: String, waitMs: Long = 20_000): PlayResult {
        val running = dev.activeAppId(ip) == InstallPlanner.DEV_APP_ID
        if (!post(ip, requestPath(running, url, format))) return PlayResult.SEND_FAILED
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end) {
            val info = playerInfo(ip)
            if (info != null && !info.error && info.state in PLAYING_STATES) return PlayResult.PLAYING
            delay(1000)
        }
        return if (dev.activeAppId(ip) == InstallPlanner.DEV_APP_ID) PlayResult.RECEIVER_OPEN_NOT_CONFIRMED
        else PlayResult.NOT_STARTED
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
        val PLAYING_STATES = setOf("play", "buffer")

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

        fun parsePlayer(xml: String): PlayerInfo? {
            val tag = Regex("<player\\b[^>]*>").find(xml)?.value ?: return null
            val state = Regex("\\bstate=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: return null
            return PlayerInfo(state, Regex("\\berror=\"true\"").containsMatchIn(tag))
        }
    }
}
