package com.mundymorning.couchcast.carreceiver

object GameCatalog {
    /** The page's <title>, else a readable version of the folder name. */
    fun titleOf(html: String, folder: String): String {
        val t = Regex("<title[^>]*>([^<]{1,80})</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()
        if (!t.isNullOrEmpty()) return t.replace("&amp;", "&")
        return folder.replace('-', ' ').replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    /** Game folder names are used in addresses, so keep them to simple characters. */
    fun isSafeId(id: String) = id.isNotEmpty() && id.length <= 60 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
