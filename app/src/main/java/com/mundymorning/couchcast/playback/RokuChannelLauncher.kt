package com.mundymorning.couchcast.playback

import com.mundymorning.couchcast.install.RokuDevClient
import com.mundymorning.couchcast.roku.RokuApp
import com.mundymorning.couchcast.roku.RokuClient
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

sealed class ChannelLaunch {
    /** The Roku reports the channel is the active app. [titleLinkSent] only says a title link was sent, not that it was honoured. */
    data class Opened(val app: RokuApp, val titleLinkSent: Boolean) : ChannelLaunch()
    /** The command was accepted but the Roku has not reported the channel opening. */
    data class NotConfirmed(val app: RokuApp) : ChannelLaunch()
    data class NotInstalled(val service: StreamingService) : ChannelLaunch()
    data class Failed(val reason: String) : ChannelLaunch()
}

/** Opens an official Roku channel through ECP using the channel id the Roku itself reports. */
class RokuChannelLauncher(
    private val ecpPort: Int = 8060,
    private val dev: RokuDevClient = RokuDevClient(ecpPort = ecpPort),
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS)).retryOnConnectionFailure(false).build()

    /** [apps] may be passed in when the caller already fetched the Roku's installed list. */
    suspend fun open(ip: String, match: ServiceMatch, apps: List<RokuApp>? = null, confirmMs: Long = 8_000): ChannelLaunch {
        val list = apps ?: try { RokuClient.apps(ip) } catch (_: IOException) {
            return ChannelLaunch.Failed("Couldn't reach the Roku. Check that the TV is on and on the same Wi-Fi.")
        }
        if (list.isEmpty()) return ChannelLaunch.Failed("The Roku didn't return its channel list. Check network control is allowed on the TV.")
        val app = ChannelMatcher.find(match.service, list) ?: return ChannelLaunch.NotInstalled(match.service)

        val deep = match.contentId != null && match.service.deepLink != DeepLink.NONE
        val path = if (deep) deepLinkPath(app.id, match) else "/launch/${app.id}"
        if (!post(ip, path)) return ChannelLaunch.Failed("The Roku didn't accept the command to open ${match.service.displayName}.")
        if (confirm(ip, app.id, confirmMs)) return ChannelLaunch.Opened(app, deep)
        // A rejected deep link can leave the channel closed; fall back to a plain launch once.
        if (deep && post(ip, "/launch/${app.id}") && confirm(ip, app.id, confirmMs)) return ChannelLaunch.Opened(app, false)
        return ChannelLaunch.NotConfirmed(app)
    }

    private suspend fun confirm(ip: String, id: String, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (dev.activeAppId(ip) == id) return true
            delay(700)
        }
        return false
    }

    private suspend fun post(ip: String, path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("http://$ip:$ecpPort$path").post(ByteArray(0).toRequestBody(null)).build())
                .execute().use { it.isSuccessful }
        } catch (_: IOException) { false }
    }

    companion object {
        /** Roku deep-linking takes contentId + mediaType. Whether a channel honours them is up to the channel (best effort). */
        fun deepLinkPath(channelId: String, match: ServiceMatch): String {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
            val mediaType = when (match.service.deepLink) {
                DeepLink.YOUTUBE_VIDEO -> "shortFormVideo"
                DeepLink.NETFLIX_TITLE -> "movie"
                DeepLink.NONE -> "movie"
            }
            return "/launch/$channelId?contentId=${enc(match.contentId.orEmpty())}&mediaType=$mediaType"
        }
    }
}
