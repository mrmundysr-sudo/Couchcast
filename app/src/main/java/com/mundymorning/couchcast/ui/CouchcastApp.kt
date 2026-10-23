package com.mundymorning.couchcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mundymorning.couchcast.MainViewModel
import com.mundymorning.couchcast.roku.Room
import com.mundymorning.couchcast.roku.RokuClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Violet = Color(0xFF6C4DFF)
private val Panel = Color(0xFF1B2030)
private val Online = Color(0xFF3DDC84)
private val Offline = Color(0xFF8A8F9C)

@Composable
fun CouchcastApp(vm: MainViewModel) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Violet,
            background = Color(0xFF0E1117),
            surface = Color(0xFF0E1117),
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var installing by rememberSaveable { mutableStateOf(false) }
            if (installing) InstallScreen(vm, onClose = { installing = false })
            else MainScreen(vm, onInstall = { installing = true })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: MainViewModel, onInstall: () -> Unit) {
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val currentIp by vm.currentIp.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(if (rooms.isEmpty()) 3 else 0) }
    val snackbar = remember { SnackbarHostState() }
    val room = rooms.firstOrNull { it.ip == currentIp }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { RoomSwitcher(rooms, room, vm) { tab = 3 } },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Remote" to "🎮", "Channels" to "📺", "Play" to "▶️", "Rooms" to "🏠", "Cast" to "📡")
                    .forEachIndexed { i, (label, glyph) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(glyph, fontSize = 20.sp) },
                            label = { Text(label) },
                        )
                    }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> RemoteScreen(vm, room)
                1 -> ChannelsScreen(vm, room)
                2 -> PlayScreen(room, onInstall)
                4 -> CastScreen()
                else -> RoomsScreen(vm, onInstall)
            }
        }
    }
}

@Composable
private fun RoomSwitcher(rooms: List<Room>, current: Room?, vm: MainViewModel, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(current?.online == true)
            Spacer(Modifier.size(8.dp))
            Text(current?.name ?: "No Room", fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("  ▾", fontSize = 16.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            rooms.forEach { r ->
                DropdownMenuItem(
                    text = { Row(verticalAlignment = Alignment.CenterVertically) { StatusDot(r.online); Spacer(Modifier.size(8.dp)); Text(r.name) } },
                    onClick = { vm.selectRoom(r.ip); open = false },
                )
            }
            DropdownMenuItem(text = { Text("Manage Rooms…") }, onClick = { open = false; onManage() })
        }
    }
}

@Composable
private fun StatusDot(online: Boolean) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) Online else Offline))
}

/** A button that fires once on press and, if [repeat], keeps firing while held. */
@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    repeat: Boolean = false,
    accent: Boolean = false,
    onKey: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    pressed -> Violet
                    accent -> Color(0xFF3A2F7A)
                    else -> Panel
                }
            )
            .pointerInput(repeat) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onKey()
                    val job = if (repeat) scope.launch {
                        delay(400)
                        while (true) {
                            onKey()
                            delay(110)
                        }
                    } else null
                    tryAwaitRelease()
                    job?.cancel()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 20.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RemoteScreen(vm: MainViewModel, room: Room?) {
    if (room == null) {
        EmptyState("Add a Roku in the Rooms tab to start.")
        return
    }
    var keyboard by remember { mutableStateOf(false) }
    val k = { key: String -> { vm.key(key) } }
    val h = 56.dp

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (room.isTv) KeyButton("⏻", Modifier.weight(1f).size(h), onKey = k("PowerOff"))
            KeyButton("←", Modifier.weight(1f).size(h), onKey = k("Back"))
            KeyButton("⌂", Modifier.weight(1f).size(h), onKey = k("Home"))
        }

        // D-pad
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                KeyButton("▲", Modifier.weight(1f).size(72.dp), repeat = true, onKey = k("Up"))
                Spacer(Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyButton("◀", Modifier.weight(1f).size(72.dp), repeat = true, onKey = k("Left"))
                KeyButton("OK", Modifier.weight(1f).size(72.dp), accent = true, onKey = k("Select"))
                KeyButton("▶", Modifier.weight(1f).size(72.dp), repeat = true, onKey = k("Right"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                KeyButton("▼", Modifier.weight(1f).size(72.dp), repeat = true, onKey = k("Down"))
                Spacer(Modifier.weight(1f))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyButton("↺", Modifier.weight(1f).size(h), onKey = k("InstantReplay"))
            KeyButton("✳", Modifier.weight(1f).size(h), onKey = k("Info"))
            KeyButton("⌨", Modifier.weight(1f).size(h), onKey = { keyboard = true })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyButton("⏪", Modifier.weight(1f).size(h), onKey = k("Rev"))
            KeyButton("⏯", Modifier.weight(1f).size(h), accent = true, onKey = k("Play"))
            KeyButton("⏩", Modifier.weight(1f).size(h), onKey = k("Fwd"))
        }
        if (room.isTv) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KeyButton("🔇", Modifier.weight(1f).size(h), onKey = k("VolumeMute"))
                KeyButton("🔉−", Modifier.weight(1f).size(h), repeat = true, onKey = k("VolumeDown"))
                KeyButton("🔊+", Modifier.weight(1f).size(h), repeat = true, onKey = k("VolumeUp"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("HDMI1", "HDMI2", "HDMI3", "HDMI4", "TV").forEach { name ->
                    val key = if (name == "TV") "InputTuner" else "Input$name"
                    KeyButton(name.replace("HDMI", "H"), Modifier.weight(1f).size(46.dp), onKey = k(key))
                }
            }
        }
    }

    if (keyboard) KeyboardDialog(onSend = { vm.typeText(it) }, onClose = { keyboard = false })
}

@Composable
private fun KeyboardDialog(onSend: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Type on TV") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Text for the TV's search or login field") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSend(text); text = "" }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

@Composable
private fun ChannelsScreen(vm: MainViewModel, room: Room?) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    if (room == null) {
        EmptyState("Add a Roku in the Rooms tab to see its channels.")
        return
    }
    if (apps.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No channels loaded", color = Offline)
            Spacer(Modifier.size(12.dp))
            OutlinedButton(onClick = { vm.loadApps() }) { Text("Reload") }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(apps, key = { it.id }) { app ->
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).background(Panel).clickable { vm.launch(app) }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = RokuClient.iconUrl(room.ip, app.id),
                    contentDescription = app.name,
                    modifier = Modifier.fillMaxWidth().size(64.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(app.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RoomsScreen(vm: MainViewModel, onInstall: () -> Unit) {
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val currentIp by vm.currentIp.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Room?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.scan() }, enabled = !scanning, modifier = Modifier.weight(1f)) {
                Text(if (scanning) "Scanning…" else "Find Rokus")
            }
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.weight(1f)) { Text("Add by IP") }
        }
        Spacer(Modifier.size(8.dp))
        OutlinedButton(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Install receiver on Roku") }
        Spacer(Modifier.size(12.dp))
        if (rooms.isEmpty()) {
            EmptyState("No Rooms yet. Tap Find Rokus, or add one by IP address (Settings > Network > About on the TV).")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rooms, key = { it.ip }) { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (r.ip == currentIp) Color(0xFF2A2360) else Panel)
                        .clickable { vm.selectRoom(r.ip) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(r.online)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 18.sp)
                        Text(
                            listOf(r.model, r.ip).filter { it.isNotBlank() }.joinToString(" • ") +
                                if (r.online) "" else " • offline",
                            fontSize = 12.sp, color = Offline,
                        )
                    }
                    TextButton(onClick = { renaming = r }) { Text("Rename") }
                    TextButton(onClick = { vm.removeRoom(r.ip) }) { Text("Remove") }
                }
            }
        }
    }

    if (adding) {
        var ip by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add by IP address") },
            text = {
                OutlinedTextField(value = ip, onValueChange = { ip = it }, singleLine = true, label = { Text("e.g. 192.168.1.50") })
            },
            confirmButton = { TextButton(onClick = { vm.addByIp(ip); adding = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    renaming?.let { r ->
        var name by remember(r.ip) { mutableStateOf(r.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this Room") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Living Room, Bedroom…") })
            },
            confirmButton = { TextButton(onClick = { vm.renameRoom(r.ip, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Offline, textAlign = TextAlign.Center)
    }
}
