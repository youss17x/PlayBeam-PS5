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

/** Quick destinations: label to path. */
private val PRESETS get() = listOf(S.presetPs5 to "/data/homebrew", S.presetUsb to "/mnt/usb0/homebrew")

@Composable
fun UploadScreen(state: AppState, goTo: (Tab) -> Unit) {
    val ctx = LocalContext.current
    val progress by UploadState.progress.collectAsState()
    val queue by UploadState.queue.collectAsState()
    var showConsole by remember { mutableStateOf(false) }
    var showDestination by remember { mutableStateOf(false) }
    var showChooser by remember { mutableStateOf(false) }
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

    fun pick(folder: Boolean) {
        showChooser = false
        if (folder) pickFolder.launch(null) else pickFiles.launch(arrayOf("*/*"))
    }

    fun startUpload() {
        state.save()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val uris = ArrayList(state.selected)
        val label = when {
            state.isTree -> treeName(uris[0])
            uris.size == 1 -> displayName(ctx, uris[0]) ?: S.oneFile
            else -> S.files(uris.size)
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
                progress.running -> Chip(S.chipSending, Pearl.Accent, Pearl.AccentSoft, Pearl.Accent)
                state.host.isBlank() -> Chip(S.chipNoPs5, Pearl.Muted, Pearl.Track)
                else -> Chip(currentConsole?.name ?: state.host.trim(), Pearl.OkText, Pearl.OkSoft, Pearl.Ok)
            }
        }

        ScreenTitle(S.sendToPs5)

        // ---- selection ----
        PearlCard(
            Modifier.shadow(16.dp, RoundedCornerShape(20.dp), ambientColor = Pearl.Ink.copy(alpha = 0.05f),
                spotColor = Pearl.Ink.copy(alpha = 0.08f)),
            onClick = { showChooser = true }
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Pearl.AccentSoft),
                    contentAlignment = Alignment.Center
                ) {
                    PIcon(
                        when {
                            state.selected.isEmpty() -> R.drawable.ic_plus
                            state.isTree -> R.drawable.ic_folder
                            else -> R.drawable.ic_file
                        }, Pearl.Accent, 26.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val (title, sub) = when {
                        state.selected.isEmpty() -> S.chooseWhatTitle to S.chooseWhatSub
                        state.isTree -> treeName(state.selected[0]) to S.folderReady
                        state.selected.size == 1 -> (displayName(ctx, state.selected[0]) ?: S.oneFile) to S.oneFileReady
                        else -> S.files(state.selected.size) to S.readyToSend
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Pearl.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (state.selected.isEmpty()) S.choose else S.change,
                    color = Pearl.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // ---- where & how ----
        PearlCard {
            SettingsRow(S.console, currentConsole?.name ?: state.host.trim().ifBlank { S.notSet },
                onClick = { showConsole = true })
            SettingsRow(S.destination, state.remote.ifBlank { "/" }, onClick = { showDestination = true })
            SettingsRow(S.transfer, if (serverUp) "PlayBeam Server" else "FTP",
                onClick = { goTo(Tab.Settings) },
                subtitle = if (serverUp) S.transferFastSub else S.transferFtpSub)
            val options = listOfNotNull(
                if (state.resume) S.optResume else null,
                S.optConn(state.connections),
                if (state.extractZips) S.optUnzip else null,
            ).joinToString(" · ")
            SettingsRow(S.options, options, onClick = { goTo(Tab.Settings) }, divider = false)
        }

        PrimaryButton(
            text = if (progress.running) S.addToQueue else S.uploadToPs5,
            icon = if (progress.running) R.drawable.ic_plus else R.drawable.ic_upload,
            onClick = ::startUpload,
            enabled = state.selected.isNotEmpty() && state.host.isNotBlank(),
        )
        if (state.host.isBlank()) {
            Text(S.setPs5First, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp))
        }

        // ---- current upload ----
        if (progress.running || progress.message.isNotEmpty()) {
            PearlCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (progress.running) S.nowSending else S.lastUpload,
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
                            Text(S.progressLine((progress.fraction * 100).toInt(), formatBytes(progress.doneBytes), formatBytes(progress.totalBytes)),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(S.filesOf(progress.doneFiles, progress.totalFiles), style = MaterialTheme.typography.bodySmall)
                        }
                        if (progress.currentFile.isNotEmpty()) Text(progress.currentFile,
                            style = MaterialTheme.typography.bodySmall, color = Pearl.Faint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (progress.message.isNotEmpty()) Text(progress.message, style = MaterialTheme.typography.bodyMedium,
                        color = if (progress.message.startsWith("❌")) Pearl.Error else Pearl.Ink)
                    if (progress.running) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(S.stop, null, { ctx.startService(serviceIntent(UploadService.ACTION_STOP)) },
                                Modifier.weight(1f))
                            if (queue.isNotEmpty()) SecondaryButton(S.stopAll, null,
                                { ctx.startService(serviceIntent(UploadService.ACTION_STOP_ALL)) }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // ---- queue ----
        if (queue.isNotEmpty()) {
            SectionLabel(S.waiting(queue.size))
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
    if (showChooser) ChooserSheet(onPick = ::pick, onDismiss = { showChooser = false })
    if (showDestination) {
        DestinationSheet(
            state,
            onBrowse = { showDestination = false; state.filesPath = state.remote.ifBlank { "/" }; goTo(Tab.Files) },
            onDismiss = { showDestination = false },
        )
    }
}

/** Bottom sheet: send a folder or files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooserSheet(onPick: (folder: Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Pearl.Bg) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(S.whatToSend, style = MaterialTheme.typography.headlineSmall)
            ChooserOption(R.drawable.ic_folder, S.aFolder, S.aFolderSub) { onPick(true) }
            ChooserOption(R.drawable.ic_file, S.filesOpt, S.filesOptSub) { onPick(false) }
        }
    }
}

@Composable
private fun ChooserOption(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    PearlCard(onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Pearl.AccentSoft),
                contentAlignment = Alignment.Center
            ) { PIcon(icon, Pearl.Accent, 24.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            PIcon(R.drawable.ic_chevron_right, Pearl.Faint, 16.dp)
        }
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
            Text(S.destination, style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(state.remote, { state.remote = it }, label = { Text(S.pathOnPs5) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = pearlFieldColors())
            SectionLabel(S.quickPicks)
            PearlCard {
                PRESETS.forEachIndexed { idx, (label, p) ->
                    SettingsRow(label, divider = idx < PRESETS.lastIndex, onClick = { state.remote = p },
                        subtitle = p,
                        trailing = {
                            if (state.remote == p) Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Pearl.Ok))
                        })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(S.browsePs5, R.drawable.ic_folder, { state.save(); onBrowse() }, Modifier.weight(1f),
                    enabled = state.host.isNotBlank())
                PrimaryButton(S.done, null, { state.save(); onDismiss() }, Modifier.weight(1f).height(48.dp))
            }
        }
    }
}
