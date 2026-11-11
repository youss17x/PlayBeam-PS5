package com.youss17x.playbeam

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Date

/** A drive shown in the storage switcher. */
private data class Storage(val label: String, val root: String, val isUsb: Boolean)

/** Something copied or cut, waiting for "Paste here". */
private data class ClipItem(val path: String, val file: RemoteFile, val cut: Boolean)

private sealed interface BrowserDialog {
    data object NewFolder : BrowserDialog
    data class Rename(val file: RemoteFile) : BrowserDialog
    data class Delete(val file: RemoteFile) : BrowserDialog
    data class MoveTo(val file: RemoteFile) : BrowserDialog
    data class Details(val file: RemoteFile) : BrowserDialog
}

private val DRIVE_NAME = Regex("(usb|ext)(\\d+)")

private fun isHomebrew(f: RemoteFile) = f.isDir && f.name.equals("homebrew", ignoreCase = true)

/** "game.zip" → "game (copy).zip", "folder" → "folder (copy)". */
private fun copyName(name: String, isDir: Boolean): String {
    val dot = name.lastIndexOf('.')
    return if (isDir || dot <= 0) "$name (copy)" else "${name.substring(0, dot)} (copy)${name.substring(dot)}"
}

/** Browses the PS5 (PlayBeam Server if running, else FTP). [onPick] returns the chosen upload destination. */
@Composable
fun BrowserScreen(
    host: String,
    port: Int,
    startPath: String,
    onPick: (String) -> Unit,
    onClose: (() -> Unit)?,
    onPathChange: (String) -> Unit = {},
    pickLabel: String = S.useThisFolder,
) {
    // PlayBeam Server when it is running on the PS5, otherwise FTP
    var session by remember { mutableStateOf<RemoteFs?>(null) }
    LaunchedEffect(host, port) {
        session = if (ServerClient.isRunning(host)) ServerSession(host) else FtpSession(host, port)
    }
    val current = session
    DisposableEffect(current) { onDispose { current?.closeAsync() } }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var path by remember { mutableStateOf(startPath.trim().ifBlank { "/" }) }
    var items by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<BrowserDialog?>(null) }

    // ---- drives ----
    var storages by remember { mutableStateOf(listOf(Storage("PS5", "/data", false))) }
    var free by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }

    LaunchedEffect(current) {
        val s = current ?: return@LaunchedEffect
        val mnt = runCatching { s.list("/mnt") }.getOrDefault(emptyList())
            .filter { it.isDir && DRIVE_NAME.matches(it.name) }
            .sortedBy { it.name }
        val space = mutableMapOf<String, Pair<Long, Long>>()
        s.freeSpace("/data")?.let { space["/data"] = it }
        val mntTotal = s.freeSpace("/mnt")?.first
        val drives = mnt.filter { d ->
            val root = "/mnt/${d.name}"
            val df = s.freeSpace(root)
            if (df != null) {
                // an empty mount point reports the same size as /mnt itself: nothing plugged in
                val plugged = df.first > 0 && df.first != mntTotal
                if (plugged) space[root] = df
                plugged
            } else {
                // FTP: no free-space info, keep folders that have something in them
                runCatching { s.list(root).isNotEmpty() }.getOrDefault(false)
            }
        }
        val usbCount = drives.count { it.name.startsWith("usb") }
        storages = listOf(Storage("PS5", "/data", false)) + drives.map { d ->
            val (kind, n) = DRIVE_NAME.matchEntire(d.name)!!.destructured
            val num = n.toInt() + 1
            val label = when {
                kind == "usb" && usbCount == 1 -> "USB"
                kind == "usb" -> "USB $num"
                num == 1 -> S.extended
                else -> "${S.extended} $num"
            }
            Storage(label, "/mnt/${d.name}", true)
        }
        free = space
    }

    // ---- copy / move with progress ----
    var clip by remember { mutableStateOf<ClipItem?>(null) }
    var opLabel by remember { mutableStateOf<String?>(null) }
    var opDone by remember { mutableLongStateOf(0L) }
    var opTotal by remember { mutableLongStateOf(0L) }
    var opJob by remember { mutableStateOf<Job?>(null) }

    fun runOp(label: String, done: String, block: suspend ((Long, Long) -> Unit) -> Unit) {
        opJob = scope.launch {
            opLabel = label; opDone = 0; opTotal = 0; error = null; info = null
            try {
                block { d, t -> opDone = d; opTotal = t }
                info = done
            } catch (e: CancellationException) {
                info = S.stopped
            } catch (e: Exception) {
                error = friendlyError(ctx, host, e)
            }
            opLabel = null
            opJob = null
            reloadKey++
        }
    }

    fun paste() {
        val c = clip ?: return
        val s = current ?: return
        var name = c.file.name
        if (joinPath(path, name) == c.path) {
            if (c.cut) { clip = null; return } // moving onto itself: nothing to do
            name = copyName(name, c.file.isDir)
        }
        val dest = joinPath(path, name)
        if (items.any { it.name == name }) {
            error = S.alreadyExistsHere(name)
            return
        }
        clip = null
        if (c.cut) runOp(S.moving(c.file.name), S.moved(c.file.name)) { p -> s.move(c.path, dest, p) }
        else runOp(S.copying(c.file.name), S.copied(c.file.name)) { p -> s.copy(c.path, dest, p) }
    }

    // ---- download to phone ----
    val prefs = remember { Store.prefs(ctx) }
    var downloadTree by remember { mutableStateOf(prefs.getString("downloadTree", null)?.let(Uri::parse)) }
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadName by remember { mutableStateOf("") }
    var downloadBytes by remember { mutableLongStateOf(0L) }

    fun hasWriteAccess(uri: Uri) =
        ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    fun startDownload(f: RemoteFile, tree: Uri) {
        val remote = joinPath(path, f.name)
        info = null
        downloadBytes = 0
        downloadName = f.name
        downloadJob = scope.launch {
            try {
                current!!.download(ctx, remote, f.isDir, tree) { name, n ->
                    downloadName = name
                    downloadBytes += n
                }
                info = S.savedToPhone(f.name, formatBytes(downloadBytes))
            } catch (e: CancellationException) {
                info = S.downloadStopped
            } catch (e: Exception) {
                error = friendlyError(ctx, host, e)
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

    // system Back goes up one folder
    BackHandler(enabled = path != "/") { path = parentPath(path) }

    LaunchedEffect(path, reloadKey, current) {
        onPathChange(path)
        val s = current ?: run { loading = true; return@LaunchedEffect }
        loading = true
        error = null
        try {
            items = s.list(path)
        } catch (e: Exception) {
            items = emptyList()
            error = friendlyError(ctx, host, e)
        }
        loading = false
    }

    fun act(block: suspend (RemoteFs) -> Unit) {
        val s = current ?: return
        scope.launch {
            loading = true
            try {
                block(s)
                reloadKey++
            } catch (e: Exception) {
                error = friendlyError(ctx, host, e)
                loading = false
            }
        }
    }

    val activeStorage = storages.lastOrNull { path == it.root || path.startsWith(it.root + "/") }

    Column(
        Modifier.fillMaxSize().background(Pearl.Bg)
            .then(if (onClose != null) Modifier.systemBarsPadding() else Modifier)
            .padding(horizontal = 20.dp, vertical = if (onClose != null) 12.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- title ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null) IconButton(onClick = onClose) { PIcon(R.drawable.ic_back, Pearl.Ink) }
            Column(Modifier.weight(1f)) {
                if (onClose != null) Text(S.ps5Files, style = MaterialTheme.typography.titleLarge)
                else ScreenTitle(S.tabFiles)
                current?.let { Text(S.via(it.via), style = MaterialTheme.typography.bodySmall) }
            }
            IconButton(onClick = { dialog = BrowserDialog.NewFolder }) { PIcon(R.drawable.ic_plus, Pearl.Ink) }
        }

        // ---- drives ----
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            storages.forEach { st ->
                StorageChip(st, selected = st == activeStorage, space = free[st.root]) { path = st.root }
            }
        }

        // ---- path ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                onClick = { path = parentPath(path) },
                enabled = path != "/",
                shape = RoundedCornerShape(12.dp),
                color = Pearl.Card,
                border = BorderStroke(1.dp, Pearl.Line),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    PIcon(R.drawable.ic_upload, if (path != "/") Pearl.Ink else Pearl.Faint, 18.dp)
                }
            }
            Text(path, style = MaterialTheme.typography.bodyMedium, color = Pearl.Muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }

        if (loading && opLabel == null) PearlProgress(null)
        error?.let { Text(it, color = Pearl.Error, style = MaterialTheme.typography.bodyMedium) }
        info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        // ---- running copy / move / download ----
        opLabel?.let { label ->
            ProgressCard(label, opDone, opTotal, onStop = { opJob?.cancel() })
        }
        if (downloadJob != null) {
            ProgressCard(S.downloading(downloadName), downloadBytes, 0, onStop = { downloadJob?.cancel() })
        }

        // ---- list ----
        PearlCard(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (!loading && error == null && items.isEmpty()) {
                    item {
                        Text(S.emptyFolder, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp))
                    }
                }
                itemsIndexed(items, key = { _, it -> it.name }) { idx, f ->
                    val full = joinPath(path, f.name)
                    FileRow(
                        f,
                        canCopy = current?.canCopy == true,
                        busy = downloadJob != null || opLabel != null,
                        onOpen = { path = full },
                        onDownload = { requestDownload(f) },
                        onCopy = { clip = ClipItem(full, f, cut = false); info = null },
                        onCut = { clip = ClipItem(full, f, cut = true); info = null },
                        onMoveTo = { dialog = BrowserDialog.MoveTo(f) },
                        onRename = { dialog = BrowserDialog.Rename(f) },
                        onDetails = { dialog = BrowserDialog.Details(f) },
                        onDelete = { dialog = BrowserDialog.Delete(f) },
                    )
                    if (idx < items.lastIndex) HorizontalDivider(color = Pearl.Divider, modifier = Modifier.padding(start = 56.dp))
                }
            }
        }

        // ---- bottom: paste bar or "upload here" ----
        val c = clip
        if (c != null) {
            PearlCard {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PIcon(if (c.cut) R.drawable.ic_cut else R.drawable.ic_copy, Pearl.Accent, 20.dp)
                    Column(Modifier.weight(1f)) {
                        Text(if (c.cut) S.move else S.copy, style = MaterialTheme.typography.labelSmall, color = Pearl.Faint)
                        Text(c.file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { clip = null }) { Text(S.cancel, color = Pearl.Muted) }
                    Button(
                        onClick = ::paste,
                        enabled = opLabel == null,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Pearl.Accent),
                    ) { Text(S.pasteHere, fontWeight = FontWeight.SemiBold) }
                }
            }
        } else {
            PrimaryButton(pickLabel, R.drawable.ic_upload, { onPick(path) }, container = Pearl.Accent)
        }
    }

    // ---- dialogs ----
    when (val d = dialog) {
        null -> Unit
        BrowserDialog.NewFolder -> NameDialog(S.newFolder, "", S.create, onDismiss = { dialog = null }) { name ->
            dialog = null
            act { it.makeDir(joinPath(path, name)) }
        }
        is BrowserDialog.Rename -> NameDialog(S.rename, d.file.name, S.rename, onDismiss = { dialog = null }) { name ->
            dialog = null
            act { it.renamePath(joinPath(path, d.file.name), joinPath(path, name)) }
        }
        is BrowserDialog.Delete -> {
            val full = joinPath(path, d.file.name)
            val owner = protectedOwner(full)
            var understood by remember(full) { mutableStateOf(false) }
            AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = Pearl.Card,
            title = { Text(S.deleteQ(d.file.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (d.file.isDir) S.deleteFolderBody else S.deleteFileBody)
                    if (owner != null) {
                        Text("⚠️ " + S.deleteProtectedWarn(owner), color = Pearl.Error,
                            style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { understood = !understood }) {
                            Checkbox(understood, { understood = it })
                            Text(S.deleteUnderstand, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = owner == null || understood, onClick = {
                    dialog = null
                    act { it.deletePath(full, d.file.isDir) }
                }) { Text(S.delete, color = if (owner == null || understood) Pearl.Error else Pearl.Faint) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(S.cancel) } },
        )
        }
        is BrowserDialog.MoveTo -> MoveToDialog(
            file = d.file,
            from = path,
            storages = storages,
            onDismiss = { dialog = null },
        ) { targetDir ->
            dialog = null
            val s = current ?: return@MoveToDialog
            val src = joinPath(path, d.file.name)
            val dest = joinPath(targetDir.trimEnd('/').ifEmpty { "/" }, d.file.name)
            if (dest != src) runOp(S.moving(d.file.name), S.movedTo(d.file.name, targetDir)) { p -> s.move(src, dest, p) }
        }
        is BrowserDialog.Details -> DetailsDialog(d.file, joinPath(path, d.file.name), current, onDismiss = { dialog = null })
    }
}

@Composable
private fun StorageChip(st: Storage, selected: Boolean, space: Pair<Long, Long>?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Pearl.AccentSoft else Pearl.Card,
        border = BorderStroke(1.dp, if (selected) Pearl.Accent else Pearl.Line),
        modifier = Modifier.widthIn(min = 150.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PIcon(if (st.isUsb) R.drawable.ic_drive else R.drawable.ic_console,
                if (selected) Pearl.Accent else Pearl.Muted, 22.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(st.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected) Pearl.Accent else Pearl.Ink)
                if (space != null && space.first > 0) {
                    Text(S.free(formatBytes(space.second)), fontSize = 11.sp, color = Pearl.Muted)
                    val used = 1f - space.second.toFloat() / space.first
                    Box(Modifier.width(90.dp).height(4.dp).clip(RoundedCornerShape(50)).background(Pearl.Track)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(used.coerceIn(0f, 1f))
                            .background(if (used > 0.9f) Pearl.Error else Pearl.Accent))
                    }
                } else {
                    Text(st.root, fontSize = 11.sp, color = Pearl.Muted)
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(label: String, done: Long, total: Long, onStop: () -> Unit) {
    PearlCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (total > 0) S.ofTotal(formatBytes(done), formatBytes(total)) else formatBytes(done),
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onStop) { Text(S.stop, color = Pearl.Error) }
            }
            PearlProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
        }
    }
}

@Composable
private fun FileRow(
    f: RemoteFile,
    canCopy: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onMoveTo: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val homebrew = isHomebrew(f)
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
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(
                when {
                    homebrew -> Pearl.OkSoft
                    f.isDir -> Pearl.AccentSoft
                    else -> Pearl.Bg
                }
            ),
            contentAlignment = Alignment.Center
        ) {
            PIcon(if (f.isDir) R.drawable.ic_folder else R.drawable.ic_file,
                when {
                    homebrew -> Pearl.Ok
                    f.isDir -> Pearl.Accent
                    else -> Pearl.Muted
                }, 16.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (homebrew) Pearl.OkText else Pearl.Ink,
                fontWeight = if (homebrew) FontWeight.SemiBold else FontWeight.Normal)
            when {
                homebrew -> Text(S.homebrewApps, style = MaterialTheme.typography.bodySmall, color = Pearl.Ok)
                !f.isDir -> Text(formatBytes(f.size), style = MaterialTheme.typography.bodySmall)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { PIcon(R.drawable.ic_more, Pearl.Faint, 20.dp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Pearl.Card) {
                val close = { menu = false }
                MenuItem(S.downloadToPhone, R.drawable.ic_download, close, enabled = !busy, action = onDownload)
                if (canCopy) MenuItem(S.copy, R.drawable.ic_copy, close, action = onCopy)
                MenuItem(S.cut, R.drawable.ic_cut, close, action = onCut)
                MenuItem(S.moveTo, R.drawable.ic_move, close, enabled = !busy, action = onMoveTo)
                MenuItem(S.rename, R.drawable.ic_edit, close, action = onRename)
                MenuItem(S.details, R.drawable.ic_info, close, action = onDetails)
                HorizontalDivider(color = Pearl.Divider)
                MenuItem(S.delete, R.drawable.ic_close, close, danger = true, action = onDelete)
            }
        }
    }
}

@Composable
/**
 * Names what a path belongs to when deleting it could break the PS5 setup or other homebrew
 * (Payload Manager, etaHEN, the autoloader, system folders), or null for ordinary files.
 */
internal fun protectedOwner(path: String): String? {
    val p = path.trimEnd('/').lowercase()
    fun under(root: String) = p == root || p.startsWith("$root/")
    return when {
        p.count { it == '/' } <= 1 -> S.ownerMainFolder
        under("/data/pldmgr") -> "Payload Manager"
        under("/data/etahen") -> "etaHEN"
        p.startsWith("/data/ps5_autoloader") || p.matches(Regex("/mnt/usb\\d/ps5_autoloader(/.*)?")) -> S.ownerAutoloader
        under("/data/playbeam") -> "PlayBeam Server"
        listOf("/user", "/system", "/system_ex", "/system_data", "/preinst", "/update").any { under(it) } -> S.ownerSystem
        else -> null
    }
}

internal fun MenuItem(
    text: String,
    icon: Int,
    close: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
    action: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text, color = if (danger) Pearl.Error else Pearl.Ink) },
        enabled = enabled,
        leadingIcon = { PIcon(icon, if (danger) Pearl.Error else if (enabled) Pearl.Ink else Pearl.Faint, 18.dp) },
        onClick = { close(); action() },
    )
}

/** Move a file or folder to another folder (another drive too). */
@Composable
private fun MoveToDialog(
    file: RemoteFile,
    from: String,
    storages: List<Storage>,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
) {
    // suggest the other drive first
    val other = storages.firstOrNull { !(from == it.root || from.startsWith(it.root + "/")) }
    var target by remember { mutableStateOf(other?.root ?: from) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pearl.Card,
        title = { Text(S.moveX(file.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(S.toFolder, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(target, { target = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = pearlFieldColors(), shape = RoundedCornerShape(14.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (storages.map { it.label to it.root } + listOf(S.thisFolder to from, S.ps5Homebrew to "/data/homebrew",
                        S.usbHomebrew to "/mnt/usb0/homebrew"))
                        .distinctBy { it.second }
                        .forEach { (label, root) ->
                            FilterChip(selected = target == root, onClick = { target = root }, label = { Text(label) })
                        }
                }
                Text(S.moveNote,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = target.startsWith("/"), onClick = { onMove(target.trim()) }) {
                Text(S.move, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.cancel) } },
    )
}

@Composable
private fun DetailsDialog(file: RemoteFile, fullPath: String, fs: RemoteFs?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var details by remember { mutableStateOf<RemoteDetails?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(fullPath) {
        try {
            details = fs?.details(fullPath, file.isDir)
        } catch (e: Exception) {
            failed = e.message
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pearl.Card,
        title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailLine(S.type, when {
                    isHomebrew(file) -> S.homebrewFolder
                    file.isDir -> S.folder
                    else -> file.name.substringAfterLast('.', "").uppercase().let { if (it.isEmpty()) S.file else S.fileOfType(it) }
                })
                DetailLine(S.location, parentPath(fullPath))
                val d = details
                when {
                    failed != null -> Text(failed!!, color = Pearl.Error, style = MaterialTheme.typography.bodySmall)
                    d == null -> PearlProgress(null)
                    else -> {
                        DetailLine(S.size, when {
                            d.size < 0 -> S.sizeUnknown
                            else -> "${formatBytes(d.size)} (${"%,d".format(d.size)} ${S.bytes})"
                        })
                        if (d.isDir && d.size >= 0) DetailLine(S.contains, S.containsLine(d.files, d.dirs))
                        d.mtime?.let {
                            val date = Date(it * 1000)
                            DetailLine(S.modified,
                                "${DateFormat.getMediumDateFormat(ctx).format(date)} ${DateFormat.getTimeFormat(ctx).format(date)}")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(S.close) } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Pearl.Faint)
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
        containerColor = Pearl.Card,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(S.name) },
                colors = pearlFieldColors(), shape = RoundedCornerShape(14.dp))
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.cancel) } },
    )
}
