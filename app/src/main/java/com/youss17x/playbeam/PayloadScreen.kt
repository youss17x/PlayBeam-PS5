package com.youss17x.playbeam

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Keeps a payload in itsPLK's Payload Manager: /data/pldmgr/payloads/<name>/<file>.
 * Uses PlayBeam Server when it runs, otherwise FTP.
 */
object PayloadManagerStore {
    const val ROOT = "/data/pldmgr/payloads"

    fun folderName(fileName: String) = fileName.substringBeforeLast('.')
        .replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "payload" }

    private suspend fun session(host: String, ftpPort: Int): RemoteFs =
        if (ServerClient.isRunning(host)) ServerSession(host) else FtpSession(host, ftpPort)

    /** True if Payload Manager already has a folder with this name. */
    suspend fun exists(host: String, ftpPort: Int, fileName: String): Boolean {
        val s = session(host, ftpPort)
        return try {
            runCatching { s.list(ROOT).any { it.isDir && it.name == folderName(fileName) } }.getOrDefault(false)
        } finally { s.closeAsync() }
    }

    suspend fun save(ctx: Context, uri: Uri, fileName: String, host: String, ftpPort: Int) {
        val bytes = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: throw IOException("Can't read the payload file")
        if (bytes.isEmpty()) throw IOException("The payload file is empty")
        val dir = "$ROOT/${folderName(fileName)}"
        val s = session(host, ftpPort)
        try {
            // replace older payload files in the folder, keep Payload Manager's own files (icon, settings)
            runCatching {
                s.list(dir).filter { !it.isDir && it.name != fileName &&
                    (it.name.endsWith(".elf", true) || it.name.endsWith(".bin", true)) }
                    .forEach { s.deletePath("$dir/${it.name}", false) }
            }
            s.writeFile("$dir/$fileName", bytes)
        } finally { s.closeAsync() }
    }
}

/** Sends an .elf / .bin payload to the PS5 payload loader over a raw TCP socket. */
object PayloadSender {
    suspend fun send(ctx: Context, uri: Uri, host: String, port: Int, onProgress: (Float) -> Unit): Int {
        val bytes = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: throw IOException("Can't read the payload file")
        return sendBytes(bytes, host, port, onProgress)
    }

    /** The PlayBeam Server payload bundled inside the app. */
    suspend fun sendBundledServer(ctx: Context, host: String, port: Int, onProgress: (Float) -> Unit): Int {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open("playbeam-server.elf").use { it.readBytes() } }.getOrNull()
        } ?: throw IOException("PlayBeam Server is not included in this build")
        return sendBytes(bytes, host, port, onProgress)
    }

    suspend fun sendBytes(bytes: ByteArray, host: String, port: Int, onProgress: (Float) -> Unit): Int =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) throw IOException("The payload file is empty")
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 5_000)
                s.soTimeout = 15_000
                val out = s.getOutputStream()
                var off = 0
                while (off < bytes.size) {
                    val n = minOf(64 * 1024, bytes.size - off)
                    out.write(bytes, off, n)
                    off += n
                    onProgress(off.toFloat() / bytes.size)
                }
                out.flush()
                s.shutdownOutput()
            }
            bytes.size
        }
}

@Composable
fun PayloadScreen(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Store.prefs(ctx) }

    var port by remember { mutableStateOf(prefs.getString("payloadPort", "9021")!!) }
    var recent by remember { mutableStateOf(Store.payloads(ctx)) }
    var sending by remember { mutableStateOf<String?>(null) }
    var sendProgress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }

    var confirmReplace by remember { mutableStateOf<RecentPayload?>(null) }

    fun doSave(p: RecentPayload) {
        val host = state.host.trim()
        scope.launch {
            sending = p.name; sendProgress = 0f; status = null
            try {
                PayloadManagerStore.save(ctx, Uri.parse(p.uri), p.name, host, state.portInt)
                statusOk = true
                status = S.pldmgrSaved(p.name)
            } catch (e: Exception) {
                statusOk = false
                status = if (!onLocalNetwork(ctx)) "❌ ${friendlyError(ctx, host, e)}"
                    else "❌ " + (e.message ?: S.sendFailed)
            }
            sending = null
        }
    }

    fun saveToManager(p: RecentPayload) {
        val host = state.host.trim()
        if (host.isBlank()) {
            statusOk = false; status = S.setPs5OrFind
            return
        }
        scope.launch {
            if (PayloadManagerStore.exists(host, state.portInt, p.name)) confirmReplace = p else doSave(p)
        }
    }

    confirmReplace?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmReplace = null },
            containerColor = Pearl.Card,
            title = { Text(S.pldmgrReplaceQ(PayloadManagerStore.folderName(p.name))) },
            text = { Text(S.pldmgrReplaceBody) },
            confirmButton = {
                TextButton(onClick = { confirmReplace = null; doSave(p) }) { Text(S.replace, color = Pearl.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = null }) { Text(S.cancel) } },
        )
    }

    fun send(p: RecentPayload) {
        val host = state.host.trim()
        if (host.isBlank()) {
            statusOk = false; status = S.setPs5OrFind
            return
        }
        prefs.edit().putString("payloadPort", port).apply()
        // most recently used first
        recent = listOf(p) + recent.filter { it.uri != p.uri }
        Store.savePayloads(ctx, recent)
        scope.launch {
            sending = p.name; sendProgress = 0f; status = null
            try {
                val size = PayloadSender.send(ctx, Uri.parse(p.uri), host, port.toIntOrNull() ?: 9021) { sendProgress = it }
                statusOk = true
                status = S.payloadSent(p.name, formatBytes(size.toLong()), host, port)
            } catch (e: Exception) {
                statusOk = false
                status = if (!onLocalNetwork(ctx)) "❌ ${friendlyError(ctx, host, e)}"
                    else "❌ " + S.payloadFailed(e.message ?: S.sendFailed)
            }
            sending = null
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // keep access so the payload can be re-sent later with one tap
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            send(RecentPayload(displayName(ctx, uri) ?: "payload", uri.toString()))
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogoMark()
            Text(APP_NAME, style = MaterialTheme.typography.titleLarge)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ScreenTitle(S.tabPayload)
            Text(S.payloadIntro,
                style = MaterialTheme.typography.bodySmall)
        }


        PearlCard {
            SettingsRow(S.console, state.host.trim().ifBlank { S.notSet })
            SettingsRow(S.loaderPort, divider = false, trailing = {
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    modifier = Modifier.width(96.dp), shape = RoundedCornerShape(12.dp),
                    colors = pearlFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            })
        }
        Text(S.loaderNote + "\n" + S.pldmgrNote,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))

        PrimaryButton(S.choosePayload, R.drawable.ic_file, { pick.launch(arrayOf("*/*")) },
            enabled = sending == null)

        if (sending != null) {
            PearlCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(S.sendingX(sending ?: ""), style = MaterialTheme.typography.titleSmall)
                    PearlProgress(sendProgress)
                }
            }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = if (statusOk) Pearl.OkText else Pearl.Error)
        }

        if (recent.isNotEmpty()) {
            SectionLabel(S.recent)
            PearlCard {
                recent.forEachIndexed { idx, p ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PIcon(R.drawable.ic_bolt, Pearl.Accent, 20.dp)
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { send(p) }, enabled = sending == null) {
                            Text(S.send, fontWeight = FontWeight.SemiBold)
                        }
                        Box {
                            var menu by remember { mutableStateOf(false) }
                            IconButton(onClick = { menu = true }, enabled = sending == null) {
                                PIcon(R.drawable.ic_more, Pearl.Faint, 18.dp)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Pearl.Card) {
                                MenuItem(S.saveToPldmgr, R.drawable.ic_download, { menu = false }) { saveToManager(p) }
                                MenuItem(S.removeFromList, R.drawable.ic_close, { menu = false }, danger = true) {
                                    recent = recent.filter { it.uri != p.uri }
                                    Store.savePayloads(ctx, recent)
                                }
                            }
                        }
                    }
                    if (idx < recent.lastIndex) HorizontalDivider(color = Pearl.Divider, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}
