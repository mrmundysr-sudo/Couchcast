package com.mundymorning.couchcast.signin

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.mundymorning.couchcast.playback.CookieSource
import com.mundymorning.couchcast.playback.SiteSession

/** The in-app browser's cookie store. Cookies stay on the phone and are never shown, logged or sent to the Roku. */
object AndroidCookieSource : CookieSource {
    override fun cookiesFor(url: String): String? = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
}

/** Which sites the user signed in to inside Couch Cast. Hostnames only: never passwords, cookies or tokens. */
class SignInStore(context: Context) {
    private val prefs = context.getSharedPreferences("couchcast_sites", Context.MODE_PRIVATE)
    fun hosts(): List<String> = prefs.getStringSet("hosts", emptySet())!!.sorted()
    fun add(host: String) = prefs.edit().putStringSet("hosts", prefs.getStringSet("hosts", emptySet())!! + host).apply()
    fun remove(host: String) = prefs.edit().putStringSet("hosts", prefs.getStringSet("hosts", emptySet())!! - host).apply()
    fun clear() = prefs.edit().remove("hosts").apply()
}

/** Clear-site-data controls for the in-app browser. */
object SiteSessions {
    fun hasSession(url: String): Boolean = !AndroidCookieSource.cookiesFor(url).isNullOrBlank()

    /** Log out of one site: expire its cookies on every domain they could be scoped to and drop its web storage. Best effort. */
    fun clearSite(host: String) {
        val cm = CookieManager.getInstance()
        val url = "https://$host"
        val names = cm.getCookie(url).orEmpty().split(';').map { it.trim().substringBefore('=') }.filter { it.isNotEmpty() }
        for (name in names) {
            cm.setCookie(url, "$name=; Max-Age=0; Path=/")
            for (d in SiteSession.cookieDomains(host)) cm.setCookie(url, "$name=; Max-Age=0; Domain=$d; Path=/")
        }
        cm.flush()
        runCatching { WebStorage.getInstance().deleteOrigin(url) }
    }

    /** Remove every cookie and all web storage held by the in-app browser. */
    fun clearAll(onDone: () -> Unit = {}) {
        CookieManager.getInstance().removeAllCookies { onDone() }
        CookieManager.getInstance().flush()
        runCatching { WebStorage.getInstance().deleteAllData() }
    }
}
