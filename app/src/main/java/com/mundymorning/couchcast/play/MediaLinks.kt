package com.mundymorning.couchcast.play

/** Pure helpers for deciding which URLs found on a web page are worth offering to the Roku. */
object MediaLinks {
    private val MEDIA = Regex("^https?://[^?#\\s]*\\.(m3u8|mpd|mp4|m4v|mov|mkv)(?:[?#].*)?$", RegexOption.IGNORE_CASE)

    private val ADAPTIVE = Regex("\\.(m3u8|mpd)$", RegexOption.IGNORE_CASE)

    fun isMediaUrl(url: String): Boolean = MEDIA.matches(url.trim())

    /** Distinct (ignoring #fragment) playable links; streaming playlists first, then the order found. */
    fun clean(urls: Collection<String>): List<String> =
        urls.map { it.trim().substringBefore('#') }.filter { isMediaUrl(it) }.distinct()
            // Main videos are usually HLS/DASH playlists; loose .mp4 files are more often previews and ads.
            .sortedBy { if (ADAPTIVE.containsMatchIn(it.substringBefore('?'))) 0 else 1 }

    /** Short human label: "host / file-name.ext". */
    fun label(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#')
        val host = noQuery.substringAfter("://").substringBefore('/')
        val file = noQuery.substringAfter("://").substringAfter('/', "").substringAfterLast('/')
        val shown = if (file.length > 28) file.take(25) + "…" else file
        return if (shown.isEmpty()) host else "$host / $shown"
    }
}
