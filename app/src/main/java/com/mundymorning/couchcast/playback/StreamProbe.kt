package com.mundymorning.couchcast.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One request's outcome. [status] is null when the network failed; [hops] counts redirects followed. */
data class Attempt(val status: Int?, val error: String? = null, val hops: Int = 0, val finalHost: String? = null) {
    val ok: Boolean get() = status != null && (status in 200..299)
}

/** The same resource requested three ways: as a bare client, as a browser from the page, and with the signed-in session. */
data class Ladder(val bare: Attempt, val context: Attempt, val session: Attempt?)

data class ProbeResult(
    val isHls: Boolean,
    val drm: String?,
    val codecs: List<String>,
    val manifest: Ladder,
    val segment: Ladder?,
    val hadSessionCookies: Boolean,
    val isDash: Boolean = false,
)

/**
 * Phone-side "why won't it play?" check. Fetches a few bytes of the stream, its playlist and its first segment
 * three ways and compares the answers. Never logs or returns header or cookie values.
 */
class StreamProbe(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build(),
) {
    private enum class Mode { BARE, CONTEXT, SESSION }

    suspend fun probe(
        url: String, referer: String?, userAgent: String?, cookies: CookieSource,
        privateOk: Set<String> = emptySet(),
    ): ProbeResult = withContext(Dispatchers.IO) {
        val client = http.newBuilder().dns(UpstreamPolicy.PolicyDns(privateOk)).build()
        val isDash = url.substringBefore('?').endsWith(".mpd", true)
        val hasCookies = !cookies.cookiesFor(url).isNullOrBlank()

        fun ladder(u: String, wholeBody: Boolean): Pair<Ladder, String?> {
            var body: String? = null
            fun go(mode: Mode): Attempt {
                if (mode == Mode.SESSION && cookies.cookiesFor(u).isNullOrBlank()) return Attempt(null, "no-cookies")
                val (a, b) = fetch(client, u, mode, referer, userAgent, cookies, wholeBody, privateOk)
                if (a.ok && body == null) body = b
                return a
            }
            val bare = go(Mode.BARE); val ctx = go(Mode.CONTEXT)
            val ses = if (hasCookies || cookies.cookiesFor(u)?.isNotBlank() == true) go(Mode.SESSION) else null
            return Ladder(bare, ctx, ses) to body
        }

        val (manifest, body) = ladder(url, wholeBody = !isDash && url.substringBefore('?').endsWith(".m3u8", true))
        var drm: String? = null
        var codecs = emptyList<String>()
        var segLadder: Ladder? = null
        var hls = false
        if (body != null && HlsPlaylist.looksLikePlaylist(body)) {
            hls = true
            drm = HlsPlaylist.drmFinding(body)
            codecs = HlsPlaylist.codecs(body)
            var mediaText: String = body
            var mediaUrl = url
            if (HlsPlaylist.isMaster(body)) {
                HlsPlaylist.firstReference(body, url)?.let { variant ->
                    val (_, vBody) = ladder(variant, wholeBody = true)
                    if (vBody != null) { mediaText = vBody; mediaUrl = variant }
                }
            }
            drm = drm ?: HlsPlaylist.drmFinding(mediaText)
            if (drm == null && !HlsPlaylist.isMaster(mediaText)) {
                HlsPlaylist.firstReference(mediaText, mediaUrl)?.let { seg -> segLadder = ladder(seg, wholeBody = false).first }
            }
        }
        ProbeResult(hls, drm, codecs, manifest, segLadder, hasCookies, isDash)
    }

    private fun fetch(
        client: OkHttpClient, start: String, mode: Mode, referer: String?, ua: String?, cookies: CookieSource,
        wholeBody: Boolean, privateOk: Set<String>,
    ): Pair<Attempt, String?> {
        var url = start
        var hops = 0
        try {
            while (true) {
                UpstreamPolicy.checkUrl(url, privateOk)?.let { return Attempt(null, "blocked") to null }
                val b = Request.Builder().url(url).get()
                if (!wholeBody) b.header("Range", "bytes=0-1023")
                if (mode != Mode.BARE) {
                    ua?.takeIf { it.isNotBlank() && '\n' !in it }?.let { b.header("User-Agent", it) }
                    referer?.takeIf { it.isNotBlank() && '\n' !in it }?.let {
                        b.header("Referer", it); b.header("Origin", it.substringBefore("://") + "://" + it.substringAfter("://").substringBefore('/'))
                    }
                }
                if (mode == Mode.SESSION) cookies.cookiesFor(url)?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
                client.newCall(b.build()).execute().use { r ->
                    val loc = r.header("Location")
                    if (r.code in 301..308 && loc != null && hops < 5) {
                        url = HlsPlaylist.resolve(url, loc) ?: return Attempt(r.code, "bad-redirect", hops) to null
                        hops++
                        return@use
                    }
                    val text = if (r.isSuccessful && wholeBody) r.body?.source()?.let { s -> s.request(512 * 1024); s.buffer.clone().readUtf8() } else null
                    return Attempt(r.code, null, hops, url.substringAfter("://").substringBefore('/')) to text
                }
            }
        } catch (e: IOException) {
            return Attempt(null, e.javaClass.simpleName, hops) to null
        }
    }
}
