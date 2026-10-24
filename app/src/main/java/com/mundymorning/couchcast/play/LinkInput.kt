package com.mundymorning.couchcast.play

/** Cleans up what the user typed or pasted into the link box. */
object LinkInput {
    /** A host name, IPv4 address or [IPv6 literal], with an optional port. No user@ part: for that, type the https:// too. */
    private val BARE_AUTHORITY = Regex("""^(\[[0-9A-Fa-f:.]+]|[\p{L}\p{N}._-]+)(:\d{1,5})?$""")

    /**
     * http(s) links only. A link typed with http:// or https:// is accepted exactly as before. A bare entry such as
     * "x.com/a" gets https:// added, but only if it starts with something that can be a host, so "javascript:alert(1)"
     * is rejected instead of becoming "https://javascript:alert(1)".
     */
    fun normalize(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val hasScheme = Regex("^(?i)https?://.+").matches(t)
        val u = if (hasScheme) t else if ("://" in t) return null else "https://$t"
        if (!Regex("^(?i)https?://[^/\\s]+.*").matches(u)) return null
        if (!hasScheme) {
            val authority = t.substringBefore('/').substringBefore('?').substringBefore('#')
            if (!BARE_AUTHORITY.matches(authority)) return null
        }
        return u
    }
}
