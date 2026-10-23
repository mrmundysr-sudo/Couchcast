package com.mundymorning.couchcast.relay

import com.mundymorning.couchcast.playback.HlsPlaylist
import com.mundymorning.couchcast.playback.UpstreamPolicy
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as UpResponse
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private class Status(private val code: Int, private val text: String) : NanoHTTPD.Response.IStatus {
    override fun getRequestStatus() = code
    override fun getDescription() = "$code $text"
}

/**
 * Local-network relay. Serves only URLs registered in a session, only to that session's Roku, only with its
 * unguessable token. It is not a general proxy: unknown ids, wrong tokens and other clients get nothing.
 */
class RelayServer(
    host: String,
    port: Int,
    private val sessions: (String) -> RelaySession?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) : NanoHTTPD(host, port) {

    private val clients = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

    override fun serve(req: IHTTPSession): NanoHTTPD.Response {
        val parts = req.uri.trim('/').split('/')
        if (parts.size != 3 || parts[0] != "r") return plain(404, "Not Found")
        val session = sessions(parts[1])
        // Same response for "no such session" and "wrong client": reveal nothing.
        if (session == null || session.closed || !sameToken(session.token, parts[1])) return plain(404, "Not Found")
        if (normalizeIp(req.headers["remote-addr"]) != session.clientIp) return plain(403, "Forbidden")
        if (req.method != Method.GET && req.method != Method.HEAD) return plain(405, "Method Not Allowed")
        val id = parts[2].substringBefore('.').toIntOrNull() ?: return plain(404, "Not Found")
        val upstream = session.lookup(id) ?: return plain(404, "Not Found")

        session.touch()
        session.stats.requests.incrementAndGet()
        return try { relay(session, upstream, req) } catch (e: IOException) {
            session.stats.lastError = e.javaClass.simpleName
            plain(502, "Bad Gateway")
        }
    }

    private fun relay(session: RelaySession, firstUrl: String, req: IHTTPSession): NanoHTTPD.Response {
        var url = firstUrl
        var resp: UpResponse? = null
        for (hop in 0..MAX_REDIRECTS) {
            UpstreamPolicy.checkUrl(url, session.privateOk)?.let { session.stats.lastError = "blocked: $it"; return plain(403, "Blocked") }
            val r = clientFor(session).newCall(upstreamRequest(session, url, req)).execute()
            session.stats.count(r.code)
            if (r.code in 301..308 && r.header("Location") != null) {
                val next = HlsPlaylist.resolve(url, r.header("Location")!!)
                r.close()
                if (next == null) return plain(502, "Bad Gateway")
                url = next
                continue
            }
            resp = r; break
        }
        val r = resp ?: return plain(508, "Too many redirects")
        val ctype = r.header("Content-Type").orEmpty()
        val isPlaylist = req.method == Method.GET && r.isSuccessful &&
            (ctype.contains("mpegurl", true) || url.substringBefore('?').endsWith(".m3u8", true))
        if (isPlaylist) {
            val body = r.use { it.body?.source()?.let { s -> s.request(MAX_PLAYLIST); s.buffer.clone().readUtf8() }.orEmpty() }
            if (HlsPlaylist.looksLikePlaylist(body)) {
                HlsPlaylist.drmFinding(body)?.let { session.stats.drmDetected = it; return plain(403, "Protected stream") }
                val rewritten = HlsPlaylist.rewrite(body, url) { abs ->
                    if (UpstreamPolicy.checkUrl(abs, session.privateOk) != null) abs else session.relayUrlFor(abs)
                }
                session.stats.rewrittenPlaylists.incrementAndGet()
                return NanoHTTPD.newFixedLengthResponse(Status(200, "OK"), "application/vnd.apple.mpegurl", rewritten)
                    .also { it.addHeader("Cache-Control", "no-store") }
            }
            return NanoHTTPD.newFixedLengthResponse(Status(r.code, "OK"), ctype.ifEmpty { "text/plain" }, body)
        }
        return passThrough(session, r, req.method == Method.HEAD)
    }

    private fun passThrough(session: RelaySession, r: UpResponse, head: Boolean): NanoHTTPD.Response {
        val length = r.header("Content-Length")?.toLongOrNull()
        val mime = r.header("Content-Type") ?: "application/octet-stream"
        val body = r.body
        val out: NanoHTTPD.Response = if (head || body == null) {
            r.close()
            NanoHTTPD.newFixedLengthResponse(Status(r.code, r.message.ifEmpty { "OK" }), mime, "")
                .also { resp -> length?.let { resp.addHeader("Content-Length", it.toString()) } }
        } else {
            val stream = CountingStream(body.byteStream(), r, session.stats)
            if (length != null) NanoHTTPD.newFixedLengthResponse(Status(r.code, r.message.ifEmpty { "OK" }), mime, stream, length)
            else NanoHTTPD.newChunkedResponse(Status(r.code, r.message.ifEmpty { "OK" }), mime, stream)
        }
        for (h in listOf("Content-Range", "Accept-Ranges", "Last-Modified")) r.header(h)?.let { out.addHeader(h, it) }
        return out
    }

    private fun upstreamRequest(session: RelaySession, url: String, req: IHTTPSession): Request {
        val ctx = session.context
        val b = Request.Builder().url(url).method(if (req.method == Method.HEAD) "HEAD" else "GET", null)
            .header("Accept", "*/*").header("Accept-Encoding", "identity")
        ctx.userAgent?.takeIf { it.isNotBlank() && '\n' !in it && '\r' !in it }?.let { b.header("User-Agent", it) }
        ctx.referer?.takeIf { it.isNotBlank() && '\n' !in it && '\r' !in it }?.let {
            b.header("Referer", it)
            b.header("Origin", it.substringBefore("://") + "://" + it.substringAfter("://").substringBefore('/'))
        }
        req.headers["range"]?.takeIf { it.startsWith("bytes=") && '\n' !in it }?.let { b.header("Range", it) }
        // Cookies only when the user opted in, only to the source's own hosts, scoped by the cookie store itself.
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        if (ctx.attachCookies && host in ctx.credentialHosts) ctx.cookieSource.cookiesFor(url)?.takeIf { it.isNotBlank() }?.let { b.header("Cookie", it) }
        return b.build()
    }

    private fun clientFor(session: RelaySession): OkHttpClient =
        clients.getOrPut(session.token) { http.newBuilder().dns(UpstreamPolicy.PolicyDns(session.privateOk)).build() }

    fun forget(token: String) { clients.remove(token) }

    private fun plain(code: Int, text: String) = NanoHTTPD.newFixedLengthResponse(Status(code, text), "text/plain", text)

    private class CountingStream(src: InputStream, private val resp: UpResponse, private val stats: RelayStats) : FilterInputStream(src) {
        override fun read(): Int = super.read().also { if (it >= 0) stats.bytes.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) stats.bytes.addAndGet(it.toLong()) }
        override fun close() { try { super.close() } finally { resp.close() } }
    }

    companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_PLAYLIST = 5L * 1024 * 1024

        fun sameToken(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        /** "/192.168.1.2", "::ffff:192.168.1.2", "fe80::1%wlan0" -> plain address text. */
        fun normalizeIp(raw: String?): String =
            raw.orEmpty().trim().removePrefix("/").substringBefore('%').removePrefix("::ffff:")
    }
}
