package com.mundymorning.couchcast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.mundymorning.couchcast.cast.CastPhase
import com.mundymorning.couchcast.cast.CastViewModel
import com.mundymorning.couchcast.play.MediaLinks

private val Muted = Color(0xFF8A8F9C)
private val Good = Color(0xFF3DDC84)
private val Bad = Color(0xFFFF6B6B)

/** Cast to Chromecast, Google TV, Android TV or a TV with Cast built in. Separate from the Roku Play tab. */
@Composable
fun CastScreen(vm: CastViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val busy = ui.phase == CastPhase.SENDING || ui.phase == CastPhase.SCANNING

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cast to a Chromecast or Google TV", fontSize = 18.sp, modifier = Modifier.weight(1f))
            if (ui.available) {
                AndroidView(
                    factory = { ctx -> MediaRouteButton(ctx).also { CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, it) } },
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Text(
            if (ui.connected) "Connected to ${ui.deviceName ?: "your TV"}."
            else "Tap the Cast icon and choose your TV. Nothing needs to be installed on it.",
            fontSize = 13.sp, color = if (ui.connected) Good else Muted,
        )
        if (!ui.available) {
            Text(ui.message.orEmpty(), color = Bad, fontSize = 14.sp)
            return@Column
        }
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true,
            label = { Text("Video link or web page") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let { url = it.trim() } }) { Text("Paste") }
            Button(onClick = { vm.play(url) }, enabled = !busy && url.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Play on TV") }
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text(ui.message.orEmpty(), fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.cancel() }) { Text("Cancel") }
            }
        } else {
            ui.message?.let {
                val color = when (ui.phase) {
                    CastPhase.PLAYING -> Good
                    CastPhase.IDLE, CastPhase.NOT_CONFIRMED, CastPhase.CHOOSE -> Muted
                    else -> Bad
                }
                Text(it, color = color, fontSize = 14.sp)
            }
        }
        ui.chosen?.let { Text("Video: " + MediaLinks.label(it), fontSize = 12.sp, color = Muted, maxLines = 1) }
        if (ui.phase == CastPhase.CHOOSE) {
            ui.candidates.forEach { c ->
                OutlinedButton(onClick = { vm.playCandidate(c) }, modifier = Modifier.fillMaxWidth()) { Text(MediaLinks.label(c), maxLines = 1) }
            }
        }
        if (ui.phase == CastPhase.PLAYING || ui.phase == CastPhase.NOT_CONFIRMED) {
            OutlinedButton(onClick = { vm.stop() }) { Text("Stop") }
        }
        Text(
            "Cast is separate from the Roku tabs, which are unchanged. Netflix, Hulu, Disney+ and other copy-protected services can't be cast this way; use their own apps' Cast button.",
            fontSize = 12.sp, color = Muted,
        )
    }
}
