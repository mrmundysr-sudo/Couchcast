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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mundymorning.couchcast.play.PlayPhase
import com.mundymorning.couchcast.play.PlayViewModel
import com.mundymorning.couchcast.roku.Room

@Composable
fun PlayScreen(room: Room?, onInstall: () -> Unit, vm: PlayViewModel = viewModel()) {
    if (room == null) {
        EmptyState("Add a Roku in the Rooms tab to start.")
        return
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val busy = ui.phase == PlayPhase.SENDING

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Play a video on ${room.name}", fontSize = 18.sp)
        Text(
            "Paste a direct video link (ending in .mp4, .m3u8 or .mpd). It plays through the Couchcast Receiver on your Roku. Web pages and streaming-site links won't work.",
            fontSize = 13.sp, color = Color(0xFF8A8F9C),
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true,
            label = { Text("Video link") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let { url = it.trim() } }) { Text("Paste") }
            Button(onClick = { vm.play(room.ip, url) }, enabled = !busy && url.isNotBlank(), modifier = Modifier.weight(1f)) {
                Text("Play on Roku")
            }
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text(ui.message.orEmpty(), fontSize = 14.sp)
            }
        } else {
            ui.message?.let {
                val color = when (ui.phase) {
                    PlayPhase.PLAYING -> Color(0xFF3DDC84)
                    PlayPhase.IDLE, PlayPhase.NOT_CONFIRMED -> Color(0xFF8A8F9C)
                    else -> Color(0xFFFF6B6B)
                }
                Text(it, color = color, fontSize = 14.sp)
            }
        }
        when (ui.phase) {
            PlayPhase.NEEDS_RECEIVER -> Button(onClick = onInstall) { Text("Open the receiver installer") }
            PlayPhase.UNKNOWN_FORMAT -> TextButton(onClick = { vm.play(room.ip, url, forceMp4 = true) }) { Text("Try anyway as an MP4") }
            PlayPhase.PLAYING, PlayPhase.NOT_CONFIRMED -> OutlinedButton(onClick = { vm.stop(room.ip) }) { Text("Stop") }
            else -> {}
        }
        Text(
            "Tip: use the Remote tab for pause, play and fast-forward while it plays. Back stops the video.",
            fontSize = 12.sp, color = Color(0xFF8A8F9C),
        )
    }
}
