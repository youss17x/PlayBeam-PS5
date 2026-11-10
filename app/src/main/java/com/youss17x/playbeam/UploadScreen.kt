package com.youss17x.playbeam

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val PRESETS = listOf("/data/homebrew", "/data/etaHEN/games", "/mnt/usb0")

@Composable
fun UploadScreen(state: AppState, goTo: (Tab) -> Unit) {
    val ctx = LocalContext.current
    val progress by UploadState.progress.collectAsState()
    val queue by UploadState.queue.collectAsState()
    var showConsole by remember { mutableStateOf(false) }
    var showDestination by remember { mutableStateOf(false) }
    val currentConsole = state.currentConsole

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

        // ---- where & how ----
        PearlCard {
            SettingsRow("Console", currentConsole?.name ?: state.host.trim().ifBlank { "Not set" },
                onClick = { showConsole = true })
            SettingsRow("Destination", state.remote.ifBlank { "/" }, onClick = { showDestination = true })
            SettingsRow("Transfer", if (serverUp) "PlayBeam Server" else "FTP",
                onClick = if (serverUp) null else ({ goTo(Tab.Payload) }),
                subtitle = if (serverUp) "Fastest — server running on the PS5" else "Tap to start PlayBeam Server for faster uploads")
            val options = listOfNotNull(
                if (state.resume) "Resume" else null,
                "${state.connections} conn.",
                if (state.extractZips) "Unzip" else null,
            ).joinToString(" · ")
            SettingsRow("Options", options, onClick = { goTo(Tab.Settings) }, divider = false)
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

    if (showConsole) ConsoleSheet(state, onDismiss = { showConsole = false })
    if (showDestination) {
        DestinationSheet(
            state,
            onBrowse = { showDestination = false; state.filesPath = state.remote.ifBlank { "/" }; goTo(Tab.Files) },
            onDismiss = { showDestination = false },
        )
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
                SecondaryButton("Browse PS5", R.drawable.ic_folder, { state.save(); onBrowse() }, Modifier.weight(1f),
                    enabled = state.host.isNotBlank())
                PrimaryButton("Done", null, { state.save(); onDismiss() }, Modifier.weight(1f).height(48.dp))
            }
        }
    }
}
