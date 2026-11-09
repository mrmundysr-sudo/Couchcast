package com.mundymorning.couchcast.carreceiver

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.io.InputStream

private class HttpStatus(private val code: Int, private val text: String) : NanoHTTPD.Response.IStatus {
    override fun getRequestStatus() = code
    override fun getDescription() = "$code $text"
}

/**
 * The receiver's local-network control server. The controller page is public; everything else needs a pairing token.
 * Only local-network clients are served. Uploads go to [uploadDir] and are played, then replaced by the next one.
 */
class ControlServer(
    port: Int,
    private val pairing: Pairing,
    private val commands: ReceiverCommands,
    private val asset: (String) -> InputStream?,
    private val uploadDir: File,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (!Lan.isLocalClient(session.headers["remote-addr"])) return json(403, "forbidden")
        val path = session.uri.trimEnd('/').ifEmpty { "/" }
        try {
            if (session.method == Method.GET && (path == "/" || path == "/controller")) return page()
            if (session.method == Method.GET && path == "/api/ping") return json(200, """{"app":"couchcast-car-receiver","v":1}""")

            val isUpload = path == "/api/upload"
            val params = HashMap<String, String>()
            if (session.method == Method.POST && !isUpload) {
                try { session.parseBody(HashMap()) } catch (_: Exception) { return json(400, "bad request") }
            }
            session.parameters.forEach { (k, v) -> v.firstOrNull()?.let { params[k] = it } }

            if (path == "/api/pair" && session.method == Method.POST) return pair(params["pin"].orEmpty())
            if (!pairing.isValid(session.headers["x-token"] ?: params["token"])) return json(401, "pair first")

            return when {
                session.method == Method.GET && path == "/api/status" -> status()
                session.method == Method.GET && path == "/api/games" -> games()
                session.method != Method.POST -> json(405, "method not allowed")
                path == "/api/play" -> play(params)
                path == "/api/upload" -> upload(session, params)
                path == "/api/control" -> control(params)
                path == "/api/game" -> game(params)
                path == "/api/input" -> input(params)
                else -> json(404, "not found")
            }
        } catch (e: Exception) {
            return json(500, "error")
        }
    }

    private fun page(): Response {
        val html = asset("controller.html")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return json(404, "no controller page")
        return NanoHTTPD.newFixedLengthResponse(HttpStatus(200, "OK"), "text/html; charset=utf-8", html).also { it.addHeader("Cache-Control", "no-store") }
    }

    private fun pair(pin: String): Response = when (val r = pairing.pair(pin)) {
        is PairResult.Ok -> json(200, """{"token":"${r.token}"}""")
        is PairResult.Locked -> json(429, "too many tries; wait ${r.seconds} s and use the new code shown on the receiver")
        PairResult.Wrong -> json(403, "wrong code")
    }

    private fun status(): Response {
        val s = commands.status()
        return json(200, """{"mode":${q(s.mode)},"title":${s.title?.let { q(it) } ?: "null"},"playing":${s.playing},"positionMs":${s.positionMs},"durationMs":${s.durationMs},"game":${s.game?.let { q(it) } ?: "null"},"error":${s.error?.let { q(it) } ?: "null"}}""")
    }

    private fun games(): Response =
        json(200, "[" + commands.games().joinToString(",") { """{"id":${q(it.id)},"title":${q(it.title)}}""" } + "]")

    private fun play(p: Map<String, String>): Response {
        val url = p["url"]?.trim().orEmpty()
        if (!Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE).matches(url)) return json(400, "enter a link that starts with http:// or https://")
        val kind = MediaKinds.detect(url, null, p["kind"]) ?: MediaKind.VIDEO   // a link with no clear type is treated as video
        return reply(commands.play(url, kind, cleanTitle(p["title"] ?: url.substringBefore('?').substringAfterLast('/'))))
    }

    private fun upload(session: IHTTPSession, p: Map<String, String>): Response {
        if (session.method != Method.POST) return json(405, "method not allowed")
        val name = p["name"].orEmpty().ifEmpty { "upload" }
        val kind = MediaKinds.detect(name, session.headers["content-type"], p["kind"]) ?: return json(415, "send a video, audio or picture file")
        val length = session.headers["content-length"]?.toLongOrNull() ?: return json(411, "missing length")
        if (length <= 0 || length > MAX_UPLOAD) return json(413, "file too large")
        uploadDir.mkdirs()
        uploadDir.listFiles()?.forEach { it.delete() }
        val ext = MediaKinds.ext(name).filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
        val file = File(uploadDir, "upload-${System.nanoTime()}.$ext")
        var left = length
        val buf = ByteArray(64 * 1024)
        file.outputStream().use { out ->
            val input = session.inputStream
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n); left -= n
            }
        }
        if (left > 0) { file.delete(); return json(400, "upload was cut off") }
        return reply(commands.play(file.toURI().toString(), kind, cleanTitle(name.substringBeforeLast('.'))))
    }

    private fun control(p: Map<String, String>): Response {
        val action = p["action"].orEmpty()
        if (action !in ACTIONS) return json(400, "unknown action")
        return if (commands.control(action, p["value"]?.toLongOrNull())) json(200, """{"ok":true}""") else json(409, "nothing is playing")
    }

    private fun game(p: Map<String, String>): Response {
        val id = p["id"].orEmpty()
        if (!GameCatalog.isSafeId(id)) return json(400, "bad game")
        return if (commands.startGame(id)) json(200, """{"ok":true}""") else json(404, "no such game")
    }

    private fun input(p: Map<String, String>): Response {
        val type = p["type"].orEmpty()
        if (type !in INPUT_TYPES) return json(400, "unknown input")
        val e = InputEvent(type, p["dx"]?.toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f, p["dy"]?.toFloatOrNull()?.coerceIn(-1f, 1f) ?: 0f, p["key"])
        return if (commands.input(e)) json(200, """{"ok":true}""") else json(409, "no game is running")
    }

    private fun reply(error: String?): Response = if (error == null) json(200, """{"ok":true}""") else json(400, error)

    private fun json(code: Int, body: String): Response {
        val text = if (body.startsWith("{") || body.startsWith("[")) body else """{"error":${q(body)}}"""
        return NanoHTTPD.newFixedLengthResponse(HttpStatus(code, if (code == 200) "OK" else "Error"), "application/json", text)
            .also { it.addHeader("Cache-Control", "no-store") }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val MAX_UPLOAD = 2L * 1024 * 1024 * 1024
        private val ACTIONS = setOf("pause", "resume", "toggle", "stop", "seekBy", "seekTo")
        private val INPUT_TYPES = setOf("move", "tap", "down", "up", "key", "exit")

        fun q(s: String): String = buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

        /** A title is shown on screen and in the car's now-playing card: one tidy line. */
        fun cleanTitle(raw: String): String = raw.filter { !it.isISOControl() }.trim().take(80)
    }
}
