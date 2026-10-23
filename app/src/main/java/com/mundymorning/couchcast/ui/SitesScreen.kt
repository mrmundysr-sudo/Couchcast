package com.mundymorning.couchcast.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mundymorning.couchcast.playback.SiteSession

/** Signed-in sites, with log-out and clear-everything controls. Couch Cast never sees passwords. */
@Composable
fun SitesScreen(
    sites: List<String>, suggestedUrl: String?, onSignIn: (String) -> Unit,
    onLogOut: (String) -> Unit, onClearAll: () -> Unit, onBack: () -> Unit,
) {
    var url by remember { mutableStateOf(suggestedUrl.orEmpty()) }
    var confirm by remember { mutableStateOf(false) }
    val problem = if (url.isBlank()) null else SiteSession.startProblem(url)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Sign in to a site", fontSize = 18.sp)
        Text(
            "Some sites only show their video to signed-in members. You sign in on the site's own page, so Couch Cast never sees or stores your password. " +
                "The login stays in this app's private browser on this phone, and is used only if you choose the phone relay for that site. It is never sent to the Roku. Ordinary public videos don't need this.",
            fontSize = 13.sp, color = Color(0xFF8A8F9C),
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Site's sign-in page (https://…)") }, isError = problem != null,
        )
        problem?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp) }
        Button(onClick = { onSignIn(url) }, enabled = url.isNotBlank() && problem == null, modifier = Modifier.fillMaxWidth()) { Text("Open sign-in browser") }

        Text("Signed-in sites", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        if (sites.isEmpty()) Text("None yet.", fontSize = 13.sp, color = Color(0xFF8A8F9C))
        sites.forEach { host ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(host, modifier = Modifier.weight(1f))
                TextButton(onClick = { onLogOut(host) }) { Text("Log out") }
            }
        }
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear all sign-in data") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Clear all sign-in data?") },
        text = { Text("This logs you out of every site in Couch Cast's browser and deletes its cookies and stored site data.") },
        confirmButton = { TextButton(onClick = { onClearAll(); confirm = false }) { Text("Clear everything") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

/**
 * The site's own sign-in page in a locked-down WebView: web pages only, https shown with its host, no file or app
 * links, no saved passwords, no credential handling by the app. Embedded sign-in that a site refuses is not worked around.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInScreen(startUrl: String, onDone: (host: String?) -> Unit) {
    var host by remember { mutableStateOf(SiteSession.hostOf(startUrl).orEmpty()) }
    var secure by remember { mutableStateOf(startUrl.startsWith("https://")) }
    var web by remember { mutableStateOf<WebView?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (secure) "🔒 $host" else "⚠ $host (not secure)", fontSize = 14.sp,
                color = if (secure) Color(0xFF3DDC84) else Color(0xFFFF6B6B), modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = { onDone(host.takeIf { it.isNotBlank() }) }) { Text("Done") }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSaveFormData(false)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !SiteSession.navigationAllowed(request.url.toString())
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            SiteSession.hostOf(url)?.let { host = it }
                            secure = url.startsWith("https://")
                        }
                        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, h: String, realm: String) = handler.cancel()
                    }
                    web = this
                    loadUrl(startUrl)
                }
            },
            onRelease = { it.stopLoading(); CookieManager.getInstance().flush(); it.destroy() },
        )
    }
}
