package com.mundymorning.couchcast.playback

/** Keeps secrets (tokens, cookies, signed query strings) out of logs, diagnostics and the UI. */
object Redactor {
    /** scheme://host/…/last-segment, with userinfo, query and fragment removed. */
    fun url(raw: String): String {
        val m = Regex("^(https?)://(?:[^/@]*@)?([^/:?#]+)(?::(\\d+))?([^?#]*)", RegexOption.IGNORE_CASE).find(raw.trim())
            ?: return "(link)"
        val (scheme, host, port, path) = m.destructured
        val last = path.substringAfterLast('/', "")
        val shortPath = when {
            path.isEmpty() || path == "/" -> ""
            '/' !in path.trimStart('/') -> "/" + last
            else -> "/…/" + last
        }
        val hasQuery = raw.contains('?')
        return "$scheme://$host${if (port.isNotEmpty()) ":$port" else ""}$shortPath${if (hasQuery) "?…" else ""}"
    }

    /** Removes any occurrence of the given secret values from free text. */
    fun text(text: String, secrets: Collection<String>): String =
        secrets.filter { it.length >= 4 }.fold(text) { acc, s -> acc.replace(s, "•••") }

    /** "name=value; other=value" -> "name=•••; other=•••" (names only, safe for diagnostics). */
    fun cookieHeader(cookies: String?): String =
        cookies.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("; ") { it.substringBefore('=') + "=•••" }
}
