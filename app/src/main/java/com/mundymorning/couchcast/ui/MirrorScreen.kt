package com.mundymorning.couchcast.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mundymorning.couchcast.playback.MirrorGuide
import com.mundymorning.couchcast.playback.PlaybackStatus
import com.mundymorning.couchcast.playback.MirrorState
import com.mundymorning.couchcast.playback.MirrorStatus
import com.mundymorning.couchcast.playback.Support

private fun externalDisplays(ctx: Context): Int =
    (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
        .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).count { it.displayId != Display.DEFAULT_DISPLAY }

/** Number of external/wireless displays Android currently reports. Updates live. */
@Composable
private fun rememberExternalDisplayCount(): Int {
    val ctx = LocalContext.current
    var n by remember { mutableIntStateOf(externalDisplays(ctx)) }
    DisposableEffect(ctx) {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) { n = externalDisplays(ctx) }
            override fun onDisplayRemoved(id: Int) { n = externalDisplays(ctx) }
            override fun onDisplayChanged(id: Int) { n = externalDisplays(ctx) }
        }
        dm.registerDisplayListener(l, null)
        onDispose { dm.unregisterDisplayListener(l) }
    }
    return n
}

/** Android has no public API to start Miracast silently, so this opens the system's own Cast screen and checks the result. */
private fun openCastSettings(ctx: Context): Boolean {
    for (action in listOf(Settings.ACTION_CAST_SETTINGS, "android.settings.WIFI_DISPLAY_SETTINGS")) {
        try { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: ActivityNotFoundException) {}
    }
    return false
}

@Composable
fun MirrorScreen(guide: MirrorGuide, tvName: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val displays = rememberExternalDisplayCount()
    val state = MirrorStatus.fromDisplays(displays)
    var opened by remember { mutableStateOf(true) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(guide.headline, fontSize = 18.sp)
        Text("TV: $tvName", fontSize = 13.sp, color = Color(0xFF8A8F9C))
        if (guide.support == Support.LIKELY)
            Text("Most Rokus support this, but it depends on the model. The first TV step shows whether yours does.", fontSize = 13.sp, color = Color(0xFF8A8F9C))
        guide.steps.forEachIndexed { i, s ->
            Column(Modifier.fillMaxWidth()) {
                Text("${i + 1}. ${s.where}", fontSize = 12.sp, color = Color(0xFF8A8F9C))
                Text(s.text, fontSize = 15.sp)
            }
        }
        if (guide.canOpenPhoneCastSettings) {
            Button(onClick = { opened = openCastSettings(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Open phone cast settings") }
            if (!opened) Text("This phone has no direct link to its cast screen. Pull down the quick settings and tap Cast, Smart View or Screen Cast.", fontSize = 13.sp, color = Color(0xFFFF6B6B))
        }
        Text(
            (if (state == MirrorState.CONNECTED) PlaybackStatus.MIRRORING else PlaybackStatus.MIRROR_NOT_CONFIRMED).label,
            fontSize = 15.sp, color = if (state == MirrorState.CONNECTED) Color(0xFF3DDC84) else Color(0xFF8A8F9C),
        )
        Text(MirrorStatus.message(state), fontSize = 14.sp, color = if (state == MirrorState.CONNECTED) Color(0xFF3DDC84) else Color(0xFF8A8F9C))
        if (guide.caveat.isNotBlank()) Text(guide.caveat, fontSize = 13.sp, color = Color(0xFF8A8F9C))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
