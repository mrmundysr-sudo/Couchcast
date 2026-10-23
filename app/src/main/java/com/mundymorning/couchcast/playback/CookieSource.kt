package com.mundymorning.couchcast.playback

/** Where session cookies come from. The app uses the WebView cookie store; tests use a map. Values must never be logged. */
fun interface CookieSource {
    /** "a=b; c=d" for a URL, honouring the cookies' own domain/path/secure scoping, or null. */
    fun cookiesFor(url: String): String?

    companion object { val NONE = CookieSource { null } }
}
