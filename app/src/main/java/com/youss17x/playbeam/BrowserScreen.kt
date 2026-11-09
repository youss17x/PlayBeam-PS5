package com.youss17x.playbeam

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
        Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← Back") }
            Text("PS5 files", style = MaterialTheme.typography.titleLarge)
        }
        Text(path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { path = parentPath(path) }, enabled = path != "/") { Text("⬆ Up") }
            OutlinedButton(onClick = { dialog = BrowserDialog.NewFolder }) { Text("➕ Folder") }
            Button(onClick = { onPick(path) }, modifier = Modifier.weight(1f)) { Text("✔ Use here") }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (downloadJob != null) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text("Downloading $downloadName", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(downloadBytes), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { downloadJob?.cancel() }) { Text("Stop") }
                }
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        downloadMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        downloadTree?.let { tree ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Phone download folder: ${treeName(tree)}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { pickDownloadFolder.launch(null) }) { Text("Change") }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (!loading && error == null && items.isEmpty()) {
                item { Text("Empty folder", style = MaterialTheme.typography.bodySmall) }
            }
            items(items, key = { it.name }) { f ->
                FileRow(
                    f,
                    onOpen = { path = joinPath(path, f.name) },
                    onRename = { dialog = BrowserDialog.Rename(f) },
                    onDelete = { dialog = BrowserDialog.Delete(f) },
                    onDownload = { requestDownload(f) },
                    downloadEnabled = downloadJob == null,
                )
                HorizontalDivider()
            }
        }
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
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (f.isDir) "📁" else "📄", Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!f.isDir) Text(formatBytes(f.size), style = MaterialTheme.typography.bodySmall)
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Download to phone") }, enabled = downloadEnabled,
                    onClick = { menu = false; onDownload() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
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
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
