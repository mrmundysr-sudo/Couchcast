package com.mundymorning.couchcast.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mundymorning.couchcast.playback.ChannelMatcher
import com.mundymorning.couchcast.playback.MirroringGuide
import com.mundymorning.couchcast.playback.Offer
import com.mundymorning.couchcast.playback.PlaybackStatus
import com.mundymorning.couchcast.playback.ServiceMatch
import com.mundymorning.couchcast.play.ChannelState
import com.mundymorning.couchcast.play.MediaLinks
import com.mundymorning.couchcast.play.PlayPhase
import com.mundymorning.couchcast.play.PlayViewModel
import com.mundymorning.couchcast.play.RelayState
import com.mundymorning.couchcast.roku.Room

private val Muted = Color(0xFF8A8F9C)
private val Good = Color(0xFF3DDC84)
private val Bad = Color(0xFFFF6B6B)
private val Panel2 = Color(0xFF1B2030)

@Composable
fun PlayScreen(room: Room?, onInstall: () -> Unit, vm: PlayViewModel = viewModel()) {
    if (room == null) {
        EmptyState("Add a Roku in the Rooms tab to start.")
        return
    }
    var screen by rememberSaveable { mutableStateOf("main") }
    var signInUrl by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = screen != "main") { screen = "main" }

    when (screen) {
        "channels" -> ChannelsPicker(vm, room, onBack = { screen = "main" }, onOpened = { screen = "main" })
        "mirror" -> MirrorScreen(
            MirroringGuide.forPlatform(vm.capabilities.platform, vm.capabilities.mirroring), room.name, onBack = { screen = "main" })
        "sites" -> {
            val sites by vm.sites.collectAsStateWithLifecycle()
            SitesScreen(sites, vm.signInStartUrl(), onSignIn = { signInUrl = it; screen = "signin" },
                onLogOut = vm::logOut, onClearAll = vm::clearAllSites, onBack = { screen = "main" })
        }
        "signin" -> {
            BackHandler { screen = "sites" }
            SignInScreen(signInUrl) { host -> host?.let(vm::signedIn); screen = "main"; vm.rescan(room.ip, host) }
        }
        else -> PlayMain(vm, room, onInstall) { screen = it }
    }
}

@Composable
private fun PlayMain(vm: PlayViewModel, room: Room, onInstall: () -> Unit, go: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var more by rememberSaveable { mutableStateOf(false) }
    var showRelayInfo by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val busy = ui.phase == PlayPhase.SENDING || ui.phase == PlayPhase.SCANNING
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.tryRelay(room.ip) }

    LaunchedEffect(url, room.ip) { vm.onLinkChanged(url, room.ip) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Play a video on ${room.name}", fontSize = 18.sp)
        Text(
            "Paste a video link or a web page that has a video on it. The app finds the video and plays it through the Couchcast Receiver on your Roku. Sites that copy-protect their video (Netflix, Hulu, Disney+, YouTube and similar) can't be played this way, but the app can open their own Roku channel.",
            fontSize = 13.sp, color = Muted,
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true,
            label = { Text("Video link or web page") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let { url = it.trim() } }) { Text("Paste") }
            Button(onClick = { vm.play(room.ip, url) }, enabled = !busy && url.isNotBlank(), modifier = Modifier.weight(1f)) {
                Text("Play on Roku")
            }
        }

        // Recognised streaming service: offer its official Roku channel; ordinary stream-finding stays available above.
        link.service?.let { match -> ServiceCard(match, link.channel, busy) { vm.openChannel(room.ip, match) } }

        if (ui.status != PlaybackStatus.IDLE && ui.status.label.isNotBlank()) {
            Text(ui.status.label, fontSize = 15.sp, color = statusColor(ui.status))
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
                    PlayPhase.PLAYING -> Good
                    PlayPhase.IDLE, PlayPhase.NOT_CONFIRMED, PlayPhase.CHOOSE -> Muted
                    else -> Bad
                }
                Text(it, color = color, fontSize = 14.sp)
            }
        }
        ui.chosen?.let { chosen ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Video: " + MediaLinks.label(chosen), fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(chosen)) }) { Text("Copy link") }
            }
        }
        when (ui.phase) {
            PlayPhase.NEEDS_RECEIVER -> Button(onClick = onInstall) { Text("Open the receiver installer") }
            PlayPhase.NO_VIDEO -> Column {
                TextButton(onClick = { vm.play(room.ip, url, forceMp4 = true) }) { Text("Try the link itself as an MP4") }
                TextButton(onClick = { go("sites") }) { Text("Members-only page? Sign in to this site") }
                TextButton(onClick = { go("mirror") }) { Text("Mirror phone screen instead") }
            }
            PlayPhase.CHOOSE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.candidates.forEach { c ->
                    OutlinedButton(onClick = { vm.playCandidate(room.ip, c) }, modifier = Modifier.fillMaxWidth()) {
                        Text(MediaLinks.label(c), maxLines = 1)
                    }
                }
            }
            PlayPhase.PLAYING, PlayPhase.NOT_CONFIRMED -> OutlinedButton(onClick = { vm.stop(room.ip) }) {
                Text(if (ui.relay == RelayState.ACTIVE) "Stop (ends the phone relay)" else "Stop")
            }
            else -> {}
        }

        // Why it didn't play directly, and only the fallbacks that fit.
        if (ui.diagnosing) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp))
            Text("Checking why it didn't play…", fontSize = 13.sp, color = Muted)
        }
        ui.diagnosis?.let { d ->
            Card2 {
                Text(d.headline, fontSize = 16.sp, color = Bad)
                Text(d.detail, fontSize = 13.sp)
                ui.offers.forEach { o ->
                    when (o) {
                        Offer.OPEN_CHANNEL -> link.service?.let { m -> Button(onClick = { vm.openChannel(room.ip, m) }, modifier = Modifier.fillMaxWidth()) { Text("Open ${m.service.displayName} on Roku") } }
                        Offer.MIRROR -> OutlinedButton(onClick = { go("mirror") }, modifier = Modifier.fillMaxWidth()) { Text("Mirror phone screen") }
                        Offer.RELAY -> Button(onClick = { showRelayInfo = true }, modifier = Modifier.fillMaxWidth()) { Text("Try phone relay") }
                        Offer.SIGN_IN -> OutlinedButton(onClick = { go("sites") }, modifier = Modifier.fillMaxWidth()) { Text("Sign in to this site") }
                    }
                }
            }
        }

        // Always reachable, never forced.
        TextButton(onClick = { more = !more }) { Text(if (more) "Hide other ways to play" else "More ways to play") }
        if (more) Card2 {
            Text("Direct playback is tried first. These are the other options:", fontSize = 13.sp, color = Muted)
            OutlinedButton(onClick = { vm.loadChannels(room.ip); go("channels") }, modifier = Modifier.fillMaxWidth()) { Text("Open a streaming channel on Roku") }
            OutlinedButton(onClick = { go("mirror") }, modifier = Modifier.fillMaxWidth()) { Text("Mirror phone screen") }
            OutlinedButton(onClick = { showRelayInfo = true }, enabled = ui.chosen != null && !busy, modifier = Modifier.fillMaxWidth()) { Text("Play the chosen video through the phone") }
            if (ui.chosen == null) Text("Find a video first, then the phone relay can carry it.", fontSize = 12.sp, color = Muted)
            OutlinedButton(onClick = { go("sites") }, modifier = Modifier.fillMaxWidth()) { Text("Sign in to a site / manage sign-ins") }
        }

        if (ui.details.isNotEmpty()) {
            TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "Troubleshooting details") }
            if (showDetails) Card2 {
                ui.details.forEach { Text(it, fontSize = 12.sp, color = Muted) }
                Text("Details never include cookies, passwords or full video addresses.", fontSize = 11.sp, color = Muted)
            }
        }
        Text(
            "Tip: use the Remote tab for pause, play and fast-forward while it plays. Back stops the video.",
            fontSize = 12.sp, color = Muted,
        )
    }

    if (showRelayInfo) RelayInfoDialog(
        ctx, canUseSession = remember { vm.hasSessionForChosen() },
        onCancel = { showRelayInfo = false },
        onGo = { useSession ->
            showRelayInfo = false
            vm.setUseSession(useSession)
            val need = Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (need) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.tryRelay(room.ip)
        },
    )
}

@Composable
private fun Card2(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel2).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

private fun statusColor(s: PlaybackStatus) = when (s) {
    PlaybackStatus.PLAYING_DIRECT, PlaybackStatus.PLAYING_RELAY, PlaybackStatus.CHANNEL_OPEN, PlaybackStatus.MIRRORING -> Good
    PlaybackStatus.FAILED, PlaybackStatus.SIGN_IN_REQUIRED, PlaybackStatus.UNSUPPORTED_PROTECTED -> Bad
    else -> Muted
}

@Composable
private fun ServiceCard(match: ServiceMatch, channel: ChannelState, busy: Boolean, onOpen: () -> Unit) {
    val name = match.service.displayName
    Card2 {
        Text("$name link detected", fontSize = 16.sp)
        Text(
            "$name protects its video, so the app can't send it to the receiver. Its own Roku channel can play it.",
            fontSize = 13.sp, color = Muted,
        )
        Text(
            when (channel) {
                ChannelState.INSTALLED -> "✔ $name is installed on your Roku."
                ChannelState.MISSING -> "$name isn't installed on your Roku. Add it from Streaming Channels on the Roku Home screen."
                ChannelState.UNKNOWN -> "Checking your Roku…"
            },
            fontSize = 13.sp, color = if (channel == ChannelState.INSTALLED) Good else Muted,
        )
        Button(onClick = onOpen, enabled = !busy && channel != ChannelState.MISSING, modifier = Modifier.fillMaxWidth()) { Text("Open $name on Roku") }
        if (match.contentId != null)
            Text("A title link will be sent, but whether $name jumps to that title is up to its channel.", fontSize = 12.sp, color = Muted)
        Text("Or tap Play on Roku above to look for an ordinary playable stream on the page.", fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun ChannelsPicker(vm: PlayViewModel, room: Room, onBack: () -> Unit, onOpened: () -> Unit) {
    val list by vm.channels.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Open a streaming channel", fontSize = 18.sp)
        Text("Channels installed on ${room.name} that Couch Cast recognises. It opens the channel; you pick what to watch.", fontSize = 13.sp, color = Muted)
        when {
            list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)); Text("Reading your Roku's channels…")
            }
            list!!.isEmpty() -> Text("No supported streaming channels were found on this Roku, or it couldn't be reached.", color = Muted)
            else -> list!!.forEach { (service, _) ->
                OutlinedButton(onClick = { vm.openChannel(room.ip, ServiceMatch(service, null)); onOpened() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open ${service.displayName}")
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun RelayInfoDialog(ctx: Context, canUseSession: Boolean, onCancel: () -> Unit, onGo: (Boolean) -> Unit) {
    var useSession by remember { mutableStateOf(false) }
    val optimized = remember {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName).not()
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Play through your phone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The phone fetches the video the way a browser would and hands it to the Roku. This helps when a site only answers browsers.")
                Text("While it plays, your phone must stay on, connected to the same Wi-Fi as the Roku, with the notification showing. If the phone turns off or leaves the Wi-Fi, the video stops.")
                Text("It works with ordinary MP4 and HLS (.m3u8) video only. It won't touch copy-protected video, and it ends when the video ends or you tap Stop.")
                if (canUseSession) Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(checked = useSession, onCheckedChange = { useSession = it })
                    Spacer(Modifier.size(10.dp))
                    Text("Include my signed-in session for this site. Used only for this playback and never sent to the Roku.", fontSize = 13.sp)
                }
                if (optimized) TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text("Battery saver may stop it. Open battery settings")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onGo(useSession) }) { Text("Start relay") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
