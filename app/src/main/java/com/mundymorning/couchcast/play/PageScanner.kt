package com.mundymorning.couchcast.play

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
import java.util.Collections
import kotlin.coroutines.resume

/**
 * Opens a web page in a hidden WebView and collects direct video links two ways:
 * the requests the page's player actually makes (.m3u8/.mpd/.mp4) and the page's own video tags.
 * It does not look at, bypass or decrypt copy-protected (DRM) video.
 */
class PageScanner(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun scan(pageUrl: String, totalMs: Long = 15_000, settleMs: Long = 3_000): List<String> =
        withContext(Dispatchers.Main) {
            val network = Collections.synchronizedList(ArrayList<String>())
            val web = WebView(context)
            try {
                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false // lets a page's player start so its stream URL is requested
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val u = request.url.toString()
                        if (request.method == "GET" && MediaLinks.isMediaUrl(u)) network += u
                        return null
                    }
                }
                web.loadUrl(pageUrl)

                val start = System.currentTimeMillis()
                var firstFoundAt = 0L
                var dom = emptyList<String>()
                while (System.currentTimeMillis() - start < totalMs) {
                    delay(1500)
                    dom = readDom(web)
                    val any = MediaLinks.clean(network.toList() + dom).isNotEmpty()
                    if (any && firstFoundAt == 0L) firstFoundAt = System.currentTimeMillis()
                    if (firstFoundAt != 0L && System.currentTimeMillis() - firstFoundAt >= settleMs) break
                }
                MediaLinks.clean(network.toList() + dom)
            } finally {
                web.stopLoading()
                web.destroy()
            }
        }

    private suspend fun readDom(web: WebView): List<String> = suspendCancellableCoroutine { cont ->
        web.evaluateJavascript(SCRIPT) { raw ->
            val urls = try {
                if (raw == null || raw == "null") emptyList() else {
                    val json = JSONTokener(raw).nextValue().toString()
                    val arr = JSONArray(json)
                    (0 until arr.length()).map { arr.getString(it) }
                }
            } catch (_: Exception) {
                emptyList()
            }
            if (cont.isActive) cont.resume(urls)
        }
    }

    private companion object {
        const val SCRIPT = "(function(){const out=[];const add=u=>{try{u=new URL(u,location.href).href;if(/^https?:/i.test(u)&&!out.includes(u))out.push(u)}catch(e){}};" +
            "document.querySelectorAll('video,video source,source,audio').forEach(e=>add(e.currentSrc||e.src||e.getAttribute('src')));" +
            "document.querySelectorAll('meta[property=\"og:video\"],meta[property=\"og:video:url\"],meta[property=\"og:video:secure_url\"],meta[name=\"twitter:player:stream\"]').forEach(e=>add(e.content));" +
            "document.querySelectorAll('a[href]').forEach(e=>{if(/\\.(m3u8|mp4|mpd)(?:[?#]|\$)/i.test(e.href))add(e.href)});" +
            "try{(document.documentElement.innerHTML.replace(/\\\\\\//g,'/').match(/https?:\\/\\/[^\"'\\s<>\\\\]+?\\.(?:m3u8|mpd|mp4)[^\"'\\s<>\\\\]*/gi)||[]).forEach(add)}catch(e){}" +
            "return JSON.stringify(out)})()"
    }
}
