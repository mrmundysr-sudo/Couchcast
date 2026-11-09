package com.mundymorning.couchcast.carreceiver

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.Display
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewAssetLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private val Muted = Color(0xFF8A8F9C)
private val Panel = Color(0xFF1B2030)
private val Good = Color(0xFF3DDC84)

@Composable
fun CarReceiverApp() {
    val mode by CarReceiver.mode.collectAsStateWithLifecycle()
    BackHandler(enabled = mode != Mode.Home) { CarReceiver.exitToHome() }
    Box(Modifier.fillMaxSize().background(Color(0xFF0E1117))) {
        when (val m = mode) {
            Mode.Home -> HomeScreen()
            is Mode.Media -> MediaScreen(m)
            is Mode.Game -> GameScreen(m)
        }
    }
}

@Composable
private fun HomeScreen() {
    val running by ReceiverRuntime.running.collectAsStateWithLifecycle()
    val port by ReceiverRuntime.port.collectAsStateWithLifecycle()
    val error by CarReceiver.error.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf(ReceiverRuntime.pairing.pin) }
    var addresses by remember { mutableStateOf(Lan.addresses()) }
    LaunchedEffect(Unit) {
        while (true) {
            pin = ReceiverRuntime.pairing.pin
            addresses = Lan.addresses()
            delay(1000)
        }
    }
    val games = remember { CarReceiver.games() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Couchcast Car Receiver", fontSize = 28.sp, color = Color.White)
        Text(
            "Send a video, a picture or a game here from your phone, and use your phone as the controller. Parked use only for anything on the car screen.",
            fontSize = 16.sp, color = Muted,
        )
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!running) {
                Text("Starting…", fontSize = 18.sp, color = Muted)
            } else {
                Text("1. On your phone's browser, open:", fontSize = 16.sp, color = Muted)
                if (addresses.isEmpty()) Text("No network address yet. Turn on Wi-Fi or your hotspot.", fontSize = 18.sp, color = Color(0xFFFF6B6B))
                addresses.forEach { Text("http://$it:$port", fontSize = 24.sp, color = Good) }
                Text("2. Enter this code:", fontSize = 16.sp, color = Muted)
                Text(pin, fontSize = 40.sp, color = Color.White)
            }
        }
        error?.let { Text(it, fontSize = 16.sp, color = Color(0xFFFF6B6B)) }

        Text("Games", fontSize = 20.sp, color = Color.White)
        if (games.isEmpty()) Text("No games are packaged in this build.", color = Muted)
        games.forEach { g ->
            Button(onClick = { CarReceiver.startGame(g.id) }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(g.title, fontSize = 20.sp) }
        }
        OutlinedButton(onClick = { ReceiverRuntime.pairing.reset() }) { Text("Forget paired phones and show a new code") }
    }
}

@Composable
private fun MediaScreen(m: Mode.Media) {
    val ctx = LocalContext.current
    val onCarScreen = remember { isOnSecondaryDisplay(ctx) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (m.kind) {
            MediaKind.PHOTO -> AsyncImage(model = m.source, contentDescription = m.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            MediaKind.VIDEO, MediaKind.AUDIO -> {
                AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            player = PlayerHolder.get(c)
                            useController = true
                            controllerShowTimeoutMs = if (m.kind == MediaKind.AUDIO) 0 else 3000
                            if (m.kind == MediaKind.AUDIO) showController()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (m.kind == MediaKind.AUDIO) Text(m.title, fontSize = 28.sp, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))
            }
        }
        if (m.kind == MediaKind.VIDEO && onCarScreen) {
            Text("Parked use only", fontSize = 14.sp, color = Color(0xFFFFC107), modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
        OutlinedButton(onClick = { CarReceiver.exitToHome() }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) { Text("Exit") }
    }
}

@Composable
private fun GameScreen(m: Mode.Game) {
    val pointer = remember { PointerState() }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var view by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(view) {
        val v = view
        if (v != null) CarReceiver.gameHost = GameInput(v, pointer) { cursor = if (pointer.visible) Offset(pointer.x, pointer.y) else null }
        onDispose {
            CarReceiver.gameHost = null
            v?.destroy()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // A wide car screen gets a phone-shaped play area: these games are made for portrait.
            val frame = if (maxWidth > maxHeight) Modifier.fillMaxHeight().aspectRatio(9f / 16f) else Modifier.fillMaxSize()
            Box(frame.onSizeChanged { pointer.resize(it.width.toFloat(), it.height.toFloat()) }) {
                AndroidView(factory = { c -> makeGameView(c, m.id).also { view = it } }, modifier = Modifier.fillMaxSize())
                cursor?.let {
                    Box(
                        Modifier.offset { IntOffset(it.x.toInt() - 14, it.y.toInt() - 14) }.size(28.dp)
                            .background(Color(0x99FF3B30), CircleShape).border(2.dp, Color.White, CircleShape),
                    )
                }
            }
        }
        OutlinedButton(onClick = { CarReceiver.exitToHome() }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { Text("Exit game") }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun makeGameView(ctx: Context, id: String): WebView {
    val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()
    return WebView(ctx).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // The game stays inside its own packaged pages; any other address is ignored.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != "appassets.androidplatform.net"
        }
        loadUrl("https://appassets.androidplatform.net/assets/games/$id/index.html")
    }
}

/** True when this screen is a car display (Android Auto runs the app on a second display) rather than the phone itself. */
private fun isOnSecondaryDisplay(ctx: Context): Boolean = try {
    Build.VERSION.SDK_INT >= 30 && ctx.display?.displayId != Display.DEFAULT_DISPLAY
} catch (_: Exception) { false }
