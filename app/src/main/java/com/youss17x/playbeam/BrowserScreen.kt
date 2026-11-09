package com.youss17x.playbeam

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface BrowserDialog {
    data object NewFolder : BrowserDialog
    data class Rename(val file: RemoteFile) : BrowserDialog
    data class Delete(val file: RemoteFile) : BrowserDialog
}

/** Browses the PS5 over FTP. [onPick] returns the folder chosen as upload destination. */
@Composable
fun BrowserScreen(
    host: String,
    port: Int,
    startPath: String,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val session = remember(host, port) { FtpSession(host, port) }
    DisposableEffect(session) { onDispose { session.closeAsync() } }
    val scope = rememberCoroutineScope()

    var path by remember { mutableStateOf(startPath.trim().ifBlank { "/" }) }
    var items by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<BrowserDialog?>(null) }

    // ---- download to phone ----
    val ctx = LocalContext.current
    val prefs = remember { Store.prefs(ctx) }
    var downloadTree by remember { mutableStateOf(prefs.getString("downloadTree", null)?.let(Uri::parse)) }
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadName by remember { mutableStateOf("") }
    var downloadBytes by remember { mutableLongStateOf(0L) }
    var downloadMsg by remember { mutableStateOf<String?>(null) }

    fun hasWriteAccess(uri: Uri) =
        ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    fun startDownload(f: RemoteFile, tree: Uri) {
        val remote = joinPath(path, f.name)
        downloadMsg = null
        downloadBytes = 0
        downloadName = f.name
        downloadJob = scope.launch {
            try {
                session.download(ctx, remote, f.isDir, tree) { name, n ->
                    downloadName = name
                    downloadBytes += n
                }
                downloadMsg = "✅ ${f.name} saved to your phone (${formatBytes(downloadBytes)})"
            } catch (e: CancellationException) {
                downloadMsg = "⏹ Download stopped"
            } catch (e: Exception) {
                downloadMsg = "❌ ${e.message ?: "Download failed"}"
            }
            downloadJob = null
        }
    }

    val pickDownloadFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            prefs.edit().putString("downloadTree", uri.toString()).apply()
            downloadTree = uri
            pendingDownload?.let { startDownload(it, uri) }
        }
        pendingDownload = null
    }

    fun requestDownload(f: RemoteFile) {
        val tree = downloadTree
        if (tree != null && hasWriteAccess(tree)) startDownload(f, tree)
        else { pendingDownload = f; pickDownloadFolder.launch(null) }
    }

    LaunchedEffect(path, reloadKey) {
        loading = true
        error = null
        try {
            items = session.list(path)
        } catch (e: Exception) {
            items = emptyList()
            error = e.message ?: "Can't connect to $host:$port"
        }
        loading = false
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            loading = true
            try {
                block()
                reloadKey++
            } catch (e: Exception) {
                error = e.message ?: "Failed"
                loading = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Pearl.Bg).systemBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { PIcon(R.drawable.ic_back, Pearl.Ink) }
            Text("PS5 files", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { dialog = BrowserDialog.NewFolder }) { PIcon(R.drawable.ic_plus, Pearl.Ink) }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                onClick = { path = parentPath(path) },
                enabled = path != "/",
                shape = RoundedCornerShape(12.dp),
                color = Pearl.Card,
                border = BorderStroke(1.dp, Pearl.Line),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    PIcon(R.drawable.ic_upload, if (path != "/") Pearl.Ink else Pearl.Faint, 18.dp)
                }
            }
            Text(path, style = MaterialTheme.typography.bodyMedium, color = Pearl.Muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }

        if (loading) PearlProgress(null)
        error?.let { Text(it, color = Pearl.Error, style = MaterialTheme.typography.bodyMedium) }

        if (downloadJob != null) {
            PearlCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Downloading $downloadName", style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatBytes(downloadBytes), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { downloadJob?.cancel() }) { Text("Stop", color = Pearl.Error) }
                    }
                    PearlProgress(null)
                }
            }
        }
        downloadMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        PearlCard(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (!loading && error == null && items.isEmpty()) {
                    item {
                        Text("Empty folder", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp))
                    }
                }
                itemsIndexed(items, key = { _, it -> it.name }) { idx, f ->
                    FileRow(
                        f,
                        onOpen = { path = joinPath(path, f.name) },
                        onRename = { dialog = BrowserDialog.Rename(f) },
                        onDelete = { dialog = BrowserDialog.Delete(f) },
                        onDownload = { requestDownload(f) },
                        downloadEnabled = downloadJob == null,
                    )
                    if (idx < items.lastIndex) HorizontalDivider(color = Pearl.Divider, modifier = Modifier.padding(start = 56.dp))
                }
            }
        }

        downloadTree?.let { tree ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads go to: ${treeName(tree)}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { pickDownloadFolder.launch(null) }) { Text("Change") }
            }
        }

        PrimaryButton("Use this folder", R.drawable.ic_folder, { onPick(path) }, container = Pearl.Accent)
    }

    when (val d = dialog) {
        null -> Unit
        BrowserDialog.NewFolder -> NameDialog("New folder", "", "Create", onDismiss = { dialog = null }) { name ->
            dialog = null
            act { session.makeDir(joinPath(path, name)) }
        }
        is BrowserDialog.Rename -> NameDialog("Rename", d.file.name, "Rename", onDismiss = { dialog = null }) { name ->
            dialog = null
            act { session.renamePath(joinPath(path, d.file.name), joinPath(path, name)) }
        }
        is BrowserDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = Pearl.Card,
            title = { Text("Delete ${d.file.name}?") },
            text = {
                Text(
                    if (d.file.isDir) "The folder and everything inside it will be deleted from the PS5. This can't be undone."
                    else "This file will be deleted from the PS5. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    act { session.deletePath(joinPath(path, d.file.name), d.file.isDir) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileRow(
    f: RemoteFile,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    downloadEnabled: Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = f.isDir, onClick = onOpen)
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(if (f.isDir) Pearl.AccentSoft else Pearl.Bg),
            contentAlignment = Alignment.Center
        ) {
            PIcon(if (f.isDir) R.drawable.ic_folder else R.drawable.ic_file,
                if (f.isDir) Pearl.Accent else Pearl.Muted, 16.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!f.isDir) Text(formatBytes(f.size), style = MaterialTheme.typography.bodySmall)
        }
        Box {
            IconButton(onClick = { menu = true }) { PIcon(R.drawable.ic_more, Pearl.Faint, 20.dp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Pearl.Card) {
                DropdownMenuItem(text = { Text("Download to phone") }, enabled = downloadEnabled,
                    leadingIcon = { PIcon(R.drawable.ic_download, Pearl.Ink, 18.dp) },
                    onClick = { menu = false; onDownload() })
                DropdownMenuItem(text = { Text("Rename") },
                    leadingIcon = { PIcon(R.drawable.ic_file, Pearl.Ink, 18.dp) },
                    onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete", color = Pearl.Error) },
                    leadingIcon = { PIcon(R.drawable.ic_close, Pearl.Error, 18.dp) },
                    onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val valid = text.isNotBlank() && '/' !in text
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        containerColor = Pearl.Card,
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") },
                colors = pearlFieldColors(), shape = RoundedCornerShape(14.dp))
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
