package com.youss17x.playbeam

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Files received from other apps through the Android share menu. */
    private val sharedUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
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

    var selected by mutableStateOf<List<Uri>>(emptyList())
    var isTree by mutableStateOf(false)

    val portInt get() = port.toIntOrNull() ?: 2121

    fun save() = prefs.edit()
        .putString("host", host.trim())
        .putString("port", port)
        .putString("remote", remote.trim())
        .putBoolean("resume", resume)
        .putInt("connections", connections)
        .apply()
}

private enum class Tab(val label: String, val icon: String) {
    Upload("Upload", "⬆️"), Payload("Payload", "🚀"), History("History", "🕘")
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
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.icon) },
                        label = { Text(t.label) },
                    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(state: AppState, onBrowse: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by UploadState.progress.collectAsState()
    val queue by UploadState.queue.collectAsState()

    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var found by remember { mutableStateOf<List<FoundDevice>?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("PlayBeam", style = MaterialTheme.typography.headlineMedium)

        // ---- PS5 connection ----
        ConsolePicker(state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.host, { state.host = it }, label = { Text("PS5 IP") },
                singleLine = true, modifier = Modifier.weight(2f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(state.port, { state.port = it.filter(Char::isDigit) }, label = { Text("Port") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }

        OutlinedButton(
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
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (scanning) "Searching… ${(scanProgress * 100).toInt()}%" else "🔍 Find my PS5") }

        if (scanning) LinearProgressIndicator(progress = { scanProgress }, modifier = Modifier.fillMaxWidth())
        scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        found?.let { list ->
            if (list.isEmpty()) {
                Text("No FTP server found. Make sure the FTP server is running on your PS5 and both devices are on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Tap your PS5:", style = MaterialTheme.typography.bodyMedium)
                list.forEach { d ->
                    Card(onClick = {
                        state.host = d.host; state.port = d.port.toString(); found = null
                        state.save()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${d.host}:${d.port}", style = MaterialTheme.typography.titleMedium)
                            if (d.banner.isNotEmpty()) Text(d.banner, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // ---- Destination ----
        OutlinedTextField(state.remote, { state.remote = it }, label = { Text("Destination path on PS5") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { p ->
                FilterChip(selected = state.remote == p, onClick = { state.remote = p },
                    label = { Text(p.substringAfterLast('/')) })
            }
        }
        OutlinedButton(onClick = onBrowse, enabled = state.host.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("📂 Browse PS5 files")
        }

        HorizontalDivider()

        // ---- What to send ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.weight(1f)) { Text("📁 Folder") }
            OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("📄 Files") }
        }
        Text(
            when {
                state.selected.isEmpty() -> "Nothing selected yet"
                state.isTree -> "Folder: ${treeName(state.selected[0])}"
                else -> "${state.selected.size} file(s) selected"
            },
            style = MaterialTheme.typography.bodyMedium
        )

        // ---- Options ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Resume / skip existing files")
                Text("Files already on the PS5 are skipped, unfinished ones continue",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = state.resume, onCheckedChange = { state.resume = it; state.save() })
        }
        Column {
            Text("Parallel connections")
            Text("More is faster for folders with many small files", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..4).forEach { n ->
                    FilterChip(selected = state.connections == n,
                        onClick = { state.connections = n; state.save() },
                        label = { Text("$n") })
                }
            }
        }

        // ---- Actions ----
        Button(onClick = ::startUpload, enabled = state.selected.isNotEmpty() && state.host.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) {
            Text(if (progress.running) "➕ Add to queue" else "⬆️ Upload to PS5")
        }
        if (progress.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ctx.startService(serviceIntent(UploadService.ACTION_STOP)) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f)) { Text("Stop current") }
                if (queue.isNotEmpty()) {
                    OutlinedButton(onClick = { ctx.startService(serviceIntent(UploadService.ACTION_STOP_ALL)) },
                        modifier = Modifier.weight(1f)) { Text("Stop all") }
                }
            }
        }

        if (progress.running || progress.message.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (progress.jobLabel.isNotEmpty())
                        Text(progress.jobLabel, style = MaterialTheme.typography.titleMedium)
                    if (progress.running) {
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress.fraction * 100).toInt()}%  •  ${progress.doneFiles}/${progress.totalFiles} files")
                        Text("${formatBytes(progress.doneBytes)} / ${formatBytes(progress.totalBytes)}  •  ${formatBytes(progress.speedBytesPerSec)}/s")
                        if (progress.currentFile.isNotEmpty())
                            Text(progress.currentFile, style = MaterialTheme.typography.bodySmall)
                    }
                    if (progress.message.isNotEmpty()) Text(progress.message)
                }
            }
        }

        if (queue.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Waiting (${queue.size})", style = MaterialTheme.typography.titleSmall)
                    queue.forEach { j ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${j.label}  →  ${j.remote}", Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = {
                                ctx.startService(serviceIntent(UploadService.ACTION_REMOVE)
                                    .putExtra(UploadService.EXTRA_JOB_ID, j.id))
                            }) { Text("✕") }
                        }
                    }
                }
            }
        }
    }
}

/** Saved PS5 consoles: pick one, save the current one, or remove. */
@Composable
private fun ConsolePicker(state: AppState) {
    val ctx = LocalContext.current
    var consoles by remember { mutableStateOf(Store.consoles(ctx)) }
    var open by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val current = consoles.firstOrNull { it.host == state.host.trim() && it.port == state.portInt }

    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current?.let { "🎮 ${it.name}" } ?: "🎮 Saved consoles (${consoles.size})")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            consoles.forEach { c ->
                DropdownMenuItem(
                    text = { Text("${c.name}  •  ${c.host}:${c.port}") },
                    onClick = {
                        state.host = c.host; state.port = c.port.toString(); state.save()
                        open = false
                    },
                    trailingIcon = {
                        TextButton(onClick = {
                            consoles = consoles.filter { it != c }
                            Store.saveConsoles(ctx, consoles)
                        }) { Text("✕") }
                    },
                )
            }
            if (consoles.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text("➕ Save current PS5…") },
                enabled = state.host.isNotBlank(),
                onClick = { open = false; saving = true },
            )
        }
    }

    if (saving) {
        var name by remember { mutableStateOf(if (consoles.isEmpty()) "My PS5" else "PS5 ${consoles.size + 1}") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save this PS5") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${state.host.trim()}:${state.port}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val c = Console(name.trim(), state.host.trim(), state.portInt)
                    consoles = consoles.filter { it.name != c.name && !(it.host == c.host && it.port == c.port) } + c
                    Store.saveConsoles(ctx, consoles)
                    saving = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }
}
