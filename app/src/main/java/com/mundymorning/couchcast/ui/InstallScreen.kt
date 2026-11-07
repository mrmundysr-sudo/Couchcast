package com.mundymorning.couchcast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mundymorning.couchcast.MainViewModel
import com.mundymorning.couchcast.install.CheckStatus
import com.mundymorning.couchcast.install.InstallUi
import com.mundymorning.couchcast.install.InstallViewModel
import com.mundymorning.couchcast.install.ReceiverState
import com.mundymorning.couchcast.install.Step

private val CardBg = Color(0xFF1B2030)
private val Muted = Color(0xFF8A8F9C)
private val Good = Color(0xFF3DDC84)
private val Bad = Color(0xFFFF6B6B)

@Composable
fun InstallScreen(main: MainViewModel, onClose: () -> Unit, vm: InstallViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackHandler { if (!vm.back()) onClose() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Install Couchcast Receiver", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val build = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
        if (build.isNotEmpty()) Text("App version $build", fontSize = 11.sp, color = Muted)
        Text(
            "Installs the receiver channel on your Roku (not the Android app). Takes a few minutes and needs only this phone and your Roku remote.",
            fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ui.busy?.let { Busy(it) }
            ui.error?.let { Note(it, Bad) }
            when (ui.step) {
                Step.PICK -> PickStep(ui, vm, main)
                Step.CHECKING -> CheckingStep(ui)
                Step.PROBLEM -> ProblemStep(ui, vm)
                Step.DEV_MODE -> DevModeStep(ui, vm)
                Step.RESTART -> RestartStep(ui, vm)
                Step.CREDENTIALS -> CredentialsStep(ui, vm)
                Step.INSTALLING, Step.VERIFYING -> Note("Please keep this screen open until it finishes.", Muted)
                Step.DONE -> DoneStep(ui, vm)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val locked = ui.step == Step.INSTALLING || ui.step == Step.VERIFYING
            TextButton(onClick = { if (!vm.back()) onClose() }, enabled = !locked) { Text(if (ui.step == Step.PICK) "Close" else "Back") }
            TextButton(onClick = { vm.cancel(); onClose() }, enabled = !locked) { Text("Cancel (keeps your progress)") }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, color = color, fontSize = 14.sp)
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(10.dp))
        Text(text, fontSize = 14.sp)
    }
}

@Composable
private fun PickStep(ui: InstallUi, vm: InstallViewModel, main: MainViewModel) {
    val rooms = vm.rooms()
    var manual by remember { mutableStateOf("") }
    if (ui.resumeAvailable) {
        Card {
            Text("You have an install in progress", fontSize = 16.sp)
            Text("Pick up where you left off for ${ui.roomName.ifBlank { ui.ip.orEmpty() }}.", fontSize = 13.sp, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.resume() }) { Text("Resume") }
                OutlinedButton(onClick = { vm.startOver() }) { Text("Start over") }
            }
        }
    }
    Text("1. Choose your Roku", fontSize = 16.sp)
    if (rooms.isEmpty()) Note("No saved Rokus yet. Add one by IP address below, or use Find Rokus on the Rooms tab.", Muted)
    rooms.forEach { r ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg)
                .clickable { vm.choose(r.ip, r.name) }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = ui.ip == r.ip, onClick = { vm.choose(r.ip, r.name) })
            Column {
                Text(r.name, fontSize = 16.sp)
                Text(listOf(r.model, r.ip).filter { it.isNotBlank() }.joinToString(" • "), fontSize = 12.sp, color = Muted)
            }
        }
    }
    OutlinedTextField(
        value = manual, onValueChange = { manual = it }, singleLine = true,
        label = { Text("Or enter an IP address, e.g. 192.168.1.50") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = { vm.chooseManual(manual) { ip -> main.addByIp(ip) } },
        enabled = manual.isNotBlank(),
    ) { Text("Use this address") }
    Button(onClick = { vm.start() }, enabled = ui.ip != null && ui.busy == null, modifier = Modifier.fillMaxWidth()) {
        Text(if (ui.ip == null) "Choose a Roku first" else "Check ${ui.roomName.ifBlank { ui.ip.orEmpty() }}")
    }
    Note("Pairing (your phone finding the Roku) is already working. Next we check developer access, then install the receiver.", Muted)
}

@Composable
private fun CheckRows(ui: InstallUi) {
    ui.checks.forEach { c ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (c.status) { CheckStatus.OK -> "✔"; CheckStatus.FAILED -> "✖"; CheckStatus.PENDING -> "…" },
                color = when (c.status) { CheckStatus.OK -> Good; CheckStatus.FAILED -> Bad; CheckStatus.PENDING -> Muted },
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(c.label + if (c.note.isNotBlank()) " (${c.note})" else "", fontSize = 14.sp)
        }
    }
}

@Composable
private fun CheckingStep(ui: InstallUi) {
    Text("2. Checking your Roku", fontSize = 16.sp)
    Card { CheckRows(ui) }
}

@Composable
private fun ProblemStep(ui: InstallUi, vm: InstallViewModel) {
    Card { CheckRows(ui) }
    ui.problem?.let {
        Card {
            Text(it.title, fontSize = 16.sp, color = Bad)
            Text(it.body, fontSize = 14.sp)
        }
    }
    Button(onClick = { vm.runCheck() }) { Text("Retry") }
}

@Composable
private fun DevModeStep(ui: InstallUi, vm: InstallViewModel) {
    Text("3. Turn on Roku Developer Mode", fontSize = 16.sp)
    Note(
        "Roku only allows this from the physical remote, so the app can't do it for you. Use your Roku remote (the app's remote won't work for this step).",
        Muted,
    )
    Card {
        Text("On the TV", fontSize = 14.sp, color = Muted)
        listOf(
            "Press Home 3 times, Up 2 times, then Right, Left, Right, Left, Right.",
            "The Developer Settings screen appears. Choose “Enable installer and restart”.",
            "Accept the license agreement.",
            "Choose a developer password and write it down. You'll type it in this app. It is not your Roku account password.",
            "Choose “Set password and reboot”. The Roku restarts (about a minute).",
        ).forEachIndexed { i, t -> Text("${i + 1}. $t", fontSize = 14.sp) }
    }
    if (ui.checks.isNotEmpty()) {
        Card {
            Text("What the last check found", fontSize = 14.sp, color = Muted)
            CheckRows(ui)
        }
    }
    Note("Already did this earlier? Tap “Check again”.", Muted)
    Button(onClick = { vm.passwordSet() }, modifier = Modifier.fillMaxWidth()) { Text("I set the password. The Roku is restarting") }
    OutlinedButton(onClick = { vm.runCheck() }, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
}

@Composable
private fun RestartStep(ui: InstallUi, vm: InstallViewModel) {
    Text("4. Waiting for your Roku to restart", fontSize = 16.sp)
    Card {
        Busy("Reconnecting… ${ui.restartWaitSeconds}s")
        Text("This screen keeps checking automatically. You can leave and come back later.", fontSize = 13.sp, color = Muted)
    }
    if (ui.checks.isNotEmpty()) Card { CheckRows(ui) }
    OutlinedButton(onClick = { vm.runCheck() }) { Text("Check again") }
    TextButton(onClick = { vm.back() }) { Text("Show the Developer Mode steps again") }
}

@Composable
private fun CredentialsStep(ui: InstallUi, vm: InstallViewModel) {
    var pw by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }
    var showPw by remember { mutableStateOf(false) }
    Text("5. Install the receiver", fontSize = 16.sp)
    Note("Developer Mode is ready on ${ui.roomName.ifBlank { ui.ip.orEmpty() }}. Roku only keeps one sideloaded channel; installing replaces any sideloaded channel already there.", Muted)
    when (ui.receiver) {
        ReceiverState.OUTDATED -> Note("An older Couchcast Receiver (${ui.installedVersion ?: "unknown version"}) is installed. This will update it to ${ui.bundledVersion}.", Muted)
        ReceiverState.OTHER_DEV_APP -> Note("A different sideloaded channel is on this Roku. Installing will replace it.", Bad)
        else -> {}
    }
    val busy = ui.busy != null
    if (ui.hasSavedPassword) {
        Button(onClick = { vm.installWithSavedPassword() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Install using saved password")
        }
        TextButton(onClick = { vm.forgetSavedPassword() }) { Text("Forget saved password") }
        Text("Or enter it again:", fontSize = 13.sp, color = Muted)
    }
    OutlinedTextField(
        value = pw, onValueChange = { pw = it }, singleLine = true,
        label = { Text("Developer password (username: rokudev)") },
        visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { showPw = !showPw }) { Text(if (showPw) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = remember, onCheckedChange = { remember = it })
        Spacer(Modifier.size(10.dp))
        Text("Remember on this phone (encrypted)", fontSize = 14.sp)
    }
    Button(onClick = { vm.install(pw, remember); pw = "" }, enabled = !busy && pw.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(if (ui.receiver == ReceiverState.OUTDATED) "Update receiver" else "Install receiver")
    }
    OutlinedButton(onClick = { vm.runCheck() }, enabled = !busy) { Text("Check again") }

    ui.confirmReplaceOf?.let {
        AlertDialog(
            onDismissRequest = { vm.runCheck() },
            title = { Text("Replace the sideloaded channel?") },
            text = { Text("Your Roku already has a different sideloaded channel. Roku keeps only one, so it will be replaced by the Couchcast Receiver.") },
            confirmButton = { TextButton(onClick = { vm.install(pw, remember, replaceConfirmed = true); pw = "" }) { Text("Replace it") } },
            dismissButton = { TextButton(onClick = { vm.runCheck() }) { Text("Don't install") } },
        )
    }
}

@Composable
private fun DoneStep(ui: InstallUi, vm: InstallViewModel) {
    Card {
        Text("✔ Receiver installed and confirmed", fontSize = 18.sp, color = Good)
        Text(
            "The Roku lists “Couchcast Receiver” ${ui.installedVersion?.let { "(version $it) " } ?: ""}as a sideloaded channel.",
            fontSize = 14.sp,
        )
        Text("What we have not tested: playing a video through the receiver. Try that from the cast screen.", fontSize = 13.sp, color = Muted)
    }
    ui.testNote?.let { Note(it, Muted) }
    Button(onClick = { vm.launchReceiver() }, modifier = Modifier.fillMaxWidth()) { Text("Launch the receiver on the TV") }
    OutlinedButton(onClick = { vm.runCheck() }, modifier = Modifier.fillMaxWidth()) { Text("Verify again") }
    OutlinedButton(onClick = { vm.reinstall() }, modifier = Modifier.fillMaxWidth()) { Text("Reinstall / update receiver") }
}
