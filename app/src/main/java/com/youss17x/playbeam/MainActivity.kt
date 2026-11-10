package com.youss17x.playbeam

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Files received from other apps through the Android share menu. */
    private val sharedUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // light background → dark status / navigation bar icons
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
        )
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            PearlTheme {
                Surface(Modifier.fillMaxSize(), color = Pearl.Bg) {
                    App(sharedUris.value, onSharedConsumed = { sharedUris.value = emptyList() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) sharedUris.value = uris
    }
}

/** Settings saved on the phone + the current selection. */
class AppState(private val prefs: SharedPreferences) {
    var host by mutableStateOf(prefs.getString("host", "")!!)
    var port by mutableStateOf(prefs.getString("port", "2121")!!)
    var remote by mutableStateOf(prefs.getString("remote", "/data/homebrew")!!)
    var resume by mutableStateOf(prefs.getBoolean("resume", true))
    var connections by mutableIntStateOf(prefs.getInt("connections", 2))
    var extractZips by mutableStateOf(prefs.getBoolean("extractZips", true))

    var selected by mutableStateOf<List<Uri>>(emptyList())
    var isTree by mutableStateOf(true)

    val portInt get() = port.toIntOrNull() ?: 2121

    fun save() = prefs.edit()
        .putString("host", host.trim())
        .putString("port", port)
        .putString("remote", remote.trim())
        .putBoolean("resume", resume)
        .putInt("connections", connections)
        .putBoolean("extractZips", extractZips)
        .apply()
}

private enum class Tab(val label: String, val icon: Int) {
    Upload("Upload", R.drawable.ic_upload),
    Payload("Payload", R.drawable.ic_bolt),
    History("History", R.drawable.ic_history),
}

@Composable
fun App(sharedUris: List<Uri>, onSharedConsumed: () -> Unit) {
    val ctx = LocalContext.current
    val state = remember { AppState(Store.prefs(ctx)) }
    var browsing by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.Upload) }

    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            state.selected = sharedUris
            state.isTree = false
            browsing = false
            tab = Tab.Upload
            onSharedConsumed()
        }
    }

    if (browsing) {
        BackHandler { browsing = false }
        BrowserScreen(
            host = state.host.trim(),
            port = state.portInt,
            startPath = state.remote,
            onPick = { state.remote = it; state.save(); browsing = false },
            onClose = { browsing = false },
        )
        return
    }

    Scaffold(
        containerColor = Pearl.Bg,
        bottomBar = {
            Column {
                HorizontalDivider(color = Pearl.Line)
                NavigationBar(containerColor = Pearl.Card, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { PIcon(t.icon, if (tab == t) Pearl.Accent else Pearl.Muted) },
                            label = { Text(t.label, fontSize = 12.sp, fontWeight = if (tab == t) FontWeight.SemiBold else FontWeight.Normal) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = Pearl.Accent,
                                unselectedTextColor = Pearl.Muted,
                                indicatorColor = Pearl.AccentSoft,
                            ),
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Upload -> UploadScreen(state, onBrowse = { state.save(); browsing = true })
                Tab.Payload -> PayloadScreen(state)
                Tab.History -> HistoryScreen()
            }
        }
    }
}

private val PRESETS = listOf("/data/homebrew", "/data/etaHEN/games", "/mnt/usb0")

@Composable
fun UploadScreen(state: AppState, onBrowse: () -> Unit) {
    val ctx = LocalContext.current
    val progress by UploadState.progress.collectAsState()
    val queue by UploadState.queue.collectAsState()
    var consoles by remember { mutableStateOf(Store.consoles(ctx)) }
    var showConsole by remember { mutableStateOf(false) }
    var showDestination by remember { mutableStateOf(false) }

    val currentConsole = consoles.firstOrNull { it.host == state.host.trim() && it.port == state.portInt }

    // is the PlayBeam Server payload running? (checked every few seconds)
    var serverUp by remember { mutableStateOf(false) }
    LaunchedEffect(state.host) {
        while (true) {
            serverUp = ServerClient.isRunning(state.host.trim())
            delay(8_000)
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { state.selected = listOf(uri); state.isTree = true }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { state.selected = uris; state.isTree = false }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun serviceIntent(action: String? = null) = Intent(ctx, UploadService::class.java).also { i ->
        if (action != null) i.action = action
    }

    fun choose() = if (state.isTree) pickFolder.launch(null) else pickFiles.launch(arrayOf("*/*"))

    fun startUpload() {
        state.save()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val uris = ArrayList(state.selected)
        val label = when {
            state.isTree -> treeName(uris[0])
            uris.size == 1 -> displayName(ctx, uris[0]) ?: "1 file"
            else -> "${uris.size} files"
        }
        val i = serviceIntent()
            .putExtra(UploadService.EXTRA_LABEL, label)
            .putExtra(UploadService.EXTRA_HOST, state.host.trim())
            .putExtra(UploadService.EXTRA_PORT, state.portInt)
            .putExtra(UploadService.EXTRA_REMOTE, state.remote.trim())
            .putExtra(UploadService.EXTRA_IS_TREE, state.isTree)
            .putExtra(UploadService.EXTRA_RESUME, state.resume)
            .putExtra(UploadService.EXTRA_CONNECTIONS, state.connections)
            .putExtra(UploadService.EXTRA_EXTRACT_ZIPS, state.extractZips)
            .putParcelableArrayListExtra(UploadService.EXTRA_URIS, uris)
            // pass read access on (needed for files shared from other apps)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = ClipData.newRawUri("files", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        i.clipData = clip
        ContextCompat.startForegroundService(ctx, i)
        state.selected = emptyList() // ready for the next one
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // ---- header ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogoMark()
            Text("PlayBeam", style = MaterialTheme.typography.titleLarge, color = Pearl.Ink)
            Spacer(Modifier.weight(1f))
            when {
                progress.running -> Chip("Sending", Pearl.Accent, Pearl.AccentSoft, Pearl.Accent)
                state.host.isBlank() -> Chip("No PS5 set", Pearl.Muted, Pearl.Track)
                else -> Chip(currentConsole?.name ?: state.host.trim(), Pearl.OkText, Pearl.OkSoft, Pearl.Ok)
            }
        }

        ScreenTitle("Send to PS5")

        // ---- Folder / Files ----
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pearl.Track).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Segment("Folder", R.drawable.ic_folder, state.isTree, Modifier.weight(1f)) {
                if (!state.isTree) { state.isTree = true; state.selected = emptyList() }
                pickFolder.launch(null)
            }
            Segment("Files", R.drawable.ic_file, !state.isTree, Modifier.weight(1f)) {
                if (state.isTree) { state.isTree = false; state.selected = emptyList() }
                pickFiles.launch(arrayOf("*/*"))
            }
        }

        // ---- selection ----
        PearlCard(
            Modifier.shadow(16.dp, RoundedCornerShape(20.dp), ambientColor = Pearl.Ink.copy(alpha = 0.05f),
                spotColor = Pearl.Ink.copy(alpha = 0.08f)),
            onClick = { choose() }
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Pearl.AccentSoft),
                    contentAlignment = Alignment.Center
                ) { PIcon(if (state.isTree) R.drawable.ic_folder else R.drawable.ic_file, Pearl.Accent, 26.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val (title, sub) = when {
                        state.selected.isEmpty() && state.isTree -> "Choose a folder" to "Everything inside, subfolders too"
                        state.selected.isEmpty() -> "Choose files" to "One or several"
                        state.isTree -> treeName(state.selected[0]) to "Folder ready to send"
                        state.selected.size == 1 -> (displayName(ctx, state.selected[0]) ?: "1 file") to "1 file ready to send"
                        else -> "${state.selected.size} files" to "Ready to send"
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Pearl.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (state.selected.isEmpty()) "Choose" else "Change",
                    color = Pearl.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // ---- settings ----
        PearlCard {
            SettingsRow("Console", currentConsole?.name ?: state.host.trim().ifBlank { "Not set" },
                onClick = { showConsole = true })
            SettingsRow("Destination", state.remote.ifBlank { "/" }, onClick = { showDestination = true })
            SettingsRow("Transfer", if (serverUp) "PlayBeam Server" else "FTP",
                subtitle = if (serverUp) "Fastest — server running on the PS5" else "Start PlayBeam Server in Payload for faster uploads")
            SettingsRow("Resume unfinished files", trailing = {
                PearlSwitch(state.resume) { state.resume = it; state.save() }
            })
            SettingsRow("Extract ZIPs on PS5",
                subtitle = "Unzips uploaded .zip files, then deletes the .zip (needs PlayBeam Server)",
                trailing = { PearlSwitch(state.extractZips) { state.extractZips = it; state.save() } })
            SettingsRow("Connections", divider = false, trailing = {
                NumberPicker((1..4).toList(), state.connections) { state.connections = it; state.save() }
            })
        }

        PrimaryButton(
            text = if (progress.running) "Add to queue" else "Upload to PS5",
            icon = if (progress.running) R.drawable.ic_plus else R.drawable.ic_upload,
            onClick = ::startUpload,
            enabled = state.selected.isNotEmpty() && state.host.isNotBlank(),
        )
        if (state.host.isBlank()) {
            Text("Set your PS5 first: tap Console.", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp))
        }

        // ---- current upload ----
        if (progress.running || progress.message.isNotEmpty()) {
            PearlCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (progress.running) "NOW SENDING" else "LAST UPLOAD",
                            style = MaterialTheme.typography.labelSmall, color = Pearl.Faint,
                            modifier = Modifier.weight(1f))
                        if (progress.running) Text("${formatBytes(progress.speedBytesPerSec)}/s",
                            color = Pearl.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (progress.jobLabel.isNotEmpty()) Text(progress.jobLabel,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (progress.running) {
                        PearlProgress(progress.fraction)
                        Row {
                            Text("${(progress.fraction * 100).toInt()}% · ${formatBytes(progress.doneBytes)} of ${formatBytes(progress.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text("${progress.doneFiles} / ${progress.totalFiles} files", style = MaterialTheme.typography.bodySmall)
                        }
                        if (progress.currentFile.isNotEmpty()) Text(progress.currentFile,
                            style = MaterialTheme.typography.bodySmall, color = Pearl.Faint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (progress.message.isNotEmpty()) Text(progress.message, style = MaterialTheme.typography.bodyMedium,
                        color = if (progress.message.startsWith("❌")) Pearl.Error else Pearl.Ink)
                    if (progress.running) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Stop", null, { ctx.startService(serviceIntent(UploadService.ACTION_STOP)) },
                                Modifier.weight(1f))
                            if (queue.isNotEmpty()) SecondaryButton("Stop all", null,
                                { ctx.startService(serviceIntent(UploadService.ACTION_STOP_ALL)) }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ---- queue ----
        if (queue.isNotEmpty()) {
            SectionLabel("Waiting · ${queue.size}")
            PearlCard {
                queue.forEachIndexed { idx, j ->
                    SettingsRow(j.label, j.remote, divider = idx < queue.lastIndex, trailing = {
                        IconButton(onClick = {
                            ctx.startService(serviceIntent(UploadService.ACTION_REMOVE)
                                .putExtra(UploadService.EXTRA_JOB_ID, j.id))
                        }) { PIcon(R.drawable.ic_close, Pearl.Faint, 18.dp) }
                    })
                }
            }
        }
    }

    if (showConsole) {
        ConsoleSheet(state, consoles, onConsolesChange = { consoles = it; Store.saveConsoles(ctx, it) },
            onDismiss = { showConsole = false })
    }
    if (showDestination) {
        DestinationSheet(state, onBrowse = { showDestination = false; onBrowse() },
            onDismiss = { showDestination = false })
    }
}

@Composable
private fun Segment(text: String, icon: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(44.dp)
            .then(if (selected) Modifier.shadow(2.dp, RoundedCornerShape(12.dp)) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Pearl.Card else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PIcon(icon, if (selected) Pearl.Ink else Pearl.Muted, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (selected) Pearl.Ink else Pearl.Muted, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

/** Bottom sheet: saved consoles, Find my PS5, manual IP / port. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleSheet(
    state: AppState,
    consoles: List<Console>,
    onConsolesChange: (List<Console>) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var found by remember { mutableStateOf<List<FoundDevice>?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun use(host: String, port: Int) {
        state.host = host; state.port = port.toString(); state.save()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Pearl.Bg) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Console", style = MaterialTheme.typography.headlineSmall)

            if (consoles.isNotEmpty()) {
                SectionLabel("Saved")
                PearlCard {
                    consoles.forEachIndexed { idx, c ->
                        val on = c.host == state.host.trim() && c.port == state.portInt
                        Row(
                            Modifier.fillMaxWidth().clickable { use(c.host, c.port); onDismiss() }
                                .heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PIcon(R.drawable.ic_console, if (on) Pearl.Accent else Pearl.Muted)
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.titleSmall,
                                    color = if (on) Pearl.Accent else Pearl.Ink)
                                Text("${c.host}:${c.port}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { onConsolesChange(consoles.filter { it != c }) }) {
                                PIcon(R.drawable.ic_close, Pearl.Faint, 18.dp)
                            }
                        }
                        if (idx < consoles.lastIndex) HorizontalDivider(color = Pearl.Divider,
                            modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }

            SecondaryButton(
                if (scanning) "Searching… ${(scanProgress * 100).toInt()}%" else "Find my PS5",
                R.drawable.ic_search,
                onClick = {
                    scope.launch {
                        scanning = true; found = null; scanError = null; scanProgress = 0f
                        try {
                            found = PS5Discovery.scan(ctx) { scanProgress = it }
                        } catch (e: Exception) {
                            scanError = e.message ?: "Search failed"
                        }
                        scanning = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning,
            )
            if (scanning) PearlProgress(scanProgress)
            scanError?.let { Text(it, color = Pearl.Error, style = MaterialTheme.typography.bodyMedium) }
            found?.let { list ->
                if (list.isEmpty()) {
                    Text("No PS5 found. Make sure PlayBeam Server or an FTP server is running on your PS5 and both devices are on the same Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    SectionLabel("Found on your network")
                    PearlCard {
                        list.forEachIndexed { idx, d ->
                            val what = listOfNotNull(
                                if (d.server) "PlayBeam Server" else null,
                                d.ftpPort?.let { "FTP $it" },
                            ).joinToString(" · ")
                            SettingsRow(d.host, what, subtitle = d.banner.ifBlank { null },
                                onClick = { use(d.host, d.ftpPort ?: state.portInt); found = null },
                                divider = idx < list.lastIndex)
                        }
                    }
                }
            }

            SectionLabel("Manual")
            Text("PlayBeam Server always uses port $SERVER_PORT automatically. The port below is only for FTP (usually 2121).",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.host, { state.host = it }, label = { Text("PS5 IP") },
                    singleLine = true, modifier = Modifier.weight(2f), shape = RoundedCornerShape(14.dp),
                    colors = pearlFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(state.port, { state.port = it.filter(Char::isDigit) }, label = { Text("FTP port") },
                    singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                    colors = pearlFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Save this PS5", R.drawable.ic_plus, { saving = true }, Modifier.weight(1f),
                    enabled = state.host.isNotBlank())
                PrimaryButton("Done", null, { state.save(); onDismiss() }, Modifier.weight(1f).height(48.dp))
            }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf(if (consoles.isEmpty()) "My PS5" else "PS5 ${consoles.size + 1}") }
        AlertDialog(
            onDismissRequest = { saving = false },
            containerColor = Pearl.Card,
            title = { Text("Save this PS5") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${state.host.trim()}:${state.port}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") },
                        colors = pearlFieldColors(), shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    state.save()
                    val c = Console(name.trim(), state.host.trim(), state.portInt)
                    onConsolesChange(consoles.filter { it.name != c.name && !(it.host == c.host && it.port == c.port) } + c)
                    saving = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }
}

/** Bottom sheet: destination path, presets, browse. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationSheet(state: AppState, onBrowse: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = { state.save(); onDismiss() }, containerColor = Pearl.Bg) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Destination", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(state.remote, { state.remote = it }, label = { Text("Path on PS5") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = pearlFieldColors())
            SectionLabel("Quick picks")
            PearlCard {
                PRESETS.forEachIndexed { idx, p ->
                    SettingsRow(p, divider = idx < PRESETS.lastIndex, onClick = { state.remote = p },
                        trailing = {
                            if (state.remote == p) Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Pearl.Accent))
                        })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Browse PS5", R.drawable.ic_folder, onBrowse, Modifier.weight(1f),
                    enabled = state.host.isNotBlank())
                PrimaryButton("Done", null, { state.save(); onDismiss() }, Modifier.weight(1f).height(48.dp))
            }
        }
    }
}
