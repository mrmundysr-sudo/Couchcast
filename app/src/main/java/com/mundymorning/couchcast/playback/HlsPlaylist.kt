package com.mundymorning.couchcast.playback

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Small, pure HLS helpers: DRM detection, codec listing and rewriting every URI so it goes through the relay. */
object HlsPlaylist {
    private val URI_ATTR = Regex("URI=\"([^\"]*)\"")
    private val URI_TAGS = listOf(
        "#EXT-X-KEY", "#EXT-X-SESSION-KEY", "#EXT-X-MAP", "#EXT-X-MEDIA", "#EXT-X-I-FRAME-STREAM-INF",
        "#EXT-X-PART", "#EXT-X-PRELOAD-HINT", "#EXT-X-RENDITION-REPORT",
    )

    fun looksLikePlaylist(body: String) = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")

    fun isMaster(text: String) = text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") }

    fun attr(line: String, name: String): String? {
        val m = Regex("(?:^|[,:])$name=(\"([^\"]*)\"|[^,]*)").find(line) ?: return null
        return m.groupValues[2].ifEmpty { m.groupValues[1].trim('"') }
    }

    /** Non-null (the key system) when the playlist uses real DRM. Plain AES-128 with an `identity` key is not DRM. */
    fun drmFinding(text: String): String? {
        for (line in text.lineSequence()) {
            if (!(line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY"))) continue
            val method = attr(line, "METHOD")
            val format = attr(line, "KEYFORMAT")
            if (method != null && method.startsWith("SAMPLE-AES", ignoreCase = true)) return method
            if (format != null && !format.equals("identity", ignoreCase = true) && format.isNotBlank()) return format
        }
        return null
    }

    fun codecs(text: String): List<String> =
        text.lineSequence().filter { it.startsWith("#EXT-X-STREAM-INF") }
            .mapNotNull { attr(it, "CODECS") }.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

    fun resolve(base: String, ref: String): String? {
        val r = ref.trim()
        if (r.isEmpty()) return null
        val abs = r.toHttpUrlOrNull()
        if (abs != null) return abs.toString()
        return base.toHttpUrlOrNull()?.resolve(r)?.toString()
    }

    /** The first media/variant reference in the playlist, resolved against [base]. */
    fun firstReference(text: String, base: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { resolve(base, it) }

    /** Replaces each URI with the value [register] returns for its absolute form. `data:` URIs are left alone. */
    fun rewrite(text: String, base: String, register: (String) -> String): String =
        text.lines().joinToString("\n") { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> line
                line.startsWith("#") ->
                    if (URI_TAGS.any { line.startsWith(it) } && line.contains("URI=\"")) {
                        URI_ATTR.replace(line) { m ->
                            val ref = m.groupValues[1]
                            if (ref.startsWith("data:", ignoreCase = true)) m.value
                            else resolve(base, ref)?.let { "URI=\"${register(it)}\"" } ?: m.value
                        }
                    } else line
                else -> resolve(base, line)?.let { register(it) } ?: line
            }
        }
}
