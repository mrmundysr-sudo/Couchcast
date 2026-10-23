package com.mundymorning.couchcast.playback

/** Pure rules for website sign-in sessions. The actual cookie store is the Android WebView's. */
object SiteSession {
    /** Cookie domains that may hold this site's session, e.g. video.example.com -> [video.example.com, example.com] (+ dotted forms). */
    fun cookieDomains(host: String): List<String> {
        val labels = host.lowercase().trim('.').split('.')
        if (labels.size < 2) return listOf(host)
        val out = mutableListOf<String>()
        for (i in 0..labels.size - 2) {
            val d = labels.drop(i).joinToString(".")
            out += d; out += ".$d"
        }
        return out
    }

    /** Sign-in must start on an https page on a public host. Returns a reason when it can't. */
    fun startProblem(url: String): String? {
        val m = Regex("^(\\w+)://(?:[^/@]*@)?([^/:?#]+)", RegexOption.IGNORE_CASE).find(url.trim()) ?: return "Enter a full web address."
        if (!m.groupValues[1].equals("https", true)) return "Sign-in pages must use https:// so your password is protected."
        return UpstreamPolicy.checkUrl(url, emptySet())?.let { "That address isn't a public website." }
    }

    /** The sign-in browser only follows web pages; scripts, files and app links are refused. */
    fun navigationAllowed(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme == "http" || scheme == "https" || url == "about:blank"
    }

    fun hostOf(url: String): String? =
        Regex("^https?://(?:[^/@]*@)?([^/:?#]+)", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.get(1)?.lowercase()
}
