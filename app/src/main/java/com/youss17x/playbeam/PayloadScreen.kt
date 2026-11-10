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

    // ---- PlayBeam Server (bundled payload) ----
    var serverUp by remember { mutableStateOf<Boolean?>(null) }
    var serverBusy by remember { mutableStateOf<String?>(null) }
    var serverMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.host) {
        while (true) {
            if (serverBusy == null) serverUp = ServerClient.isRunning(state.host.trim())
            delay(8_000)
        }
    }

    fun startServer() {
        val host = state.host.trim()
        if (host.isBlank()) { serverMsg = "Set your PS5 first (Upload → Console)."; return }
        scope.launch {
            serverBusy = "Sending PlayBeam Server…"; serverMsg = null
            try {
                PayloadSender.sendBundledServer(ctx, host, port.toIntOrNull() ?: 9021) { }
                serverBusy = "Starting…"
                var up = false
                repeat(8) { if (!up) { delay(1_000); up = ServerClient.isRunning(host) } }
                serverUp = up
                serverMsg = if (up) "✅ PlayBeam Server is running — uploads now use it automatically"
                    else "Sent, but the server didn't answer. Check the PS5 notifications."
            } catch (e: Exception) {
                serverMsg = if (!onLocalNetwork(ctx)) "❌ ${friendlyError(ctx, host, e)}"
                    else "❌ ${e.message ?: "Send failed"}. Is the ELF loader running on port ${port}?"
            }
            serverBusy = null
        }
    }

    fun stopServer() {
        val host = state.host.trim()
        scope.launch {
            serverBusy = "Stopping…"
            runCatching { withContext(Dispatchers.IO) { ServerClient(host).use { it.shutdownServer() } } }
            delay(500)
            serverUp = ServerClient.isRunning(host)
            serverMsg = if (serverUp == false) "PlayBeam Server stopped" else null
            serverBusy = null
        }
    }

    fun send(p: RecentPayload) {
        val host = state.host.trim()
        if (host.isBlank()) {
            statusOk = false; status = "Set the PS5 IP on the Upload tab first (or use Find my PS5)."
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
                status = "✅ ${p.name} sent (${formatBytes(size.toLong())}) to $host:$port"
            } catch (e: Exception) {
                statusOk = false
                status = if (!onLocalNetwork(ctx)) "❌ ${friendlyError(ctx, host, e)}"
                    else "❌ ${e.message ?: "Send failed"}. Is the payload loader running on the PS5?"
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
            Text("PlayBeam", style = MaterialTheme.typography.titleLarge)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ScreenTitle("Payload")
            Text("Start PlayBeam Server for the fastest uploads, or send any .elf / .bin payload to the PS5 loader.",
                style = MaterialTheme.typography.bodySmall)
        }


        PearlCard {
            SettingsRow("Console", state.host.trim().ifBlank { "Not set" })
            SettingsRow("Loader port", divider = false, trailing = {
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    modifier = Modifier.width(96.dp), shape = RoundedCornerShape(12.dp),
                    colors = pearlFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            })
        }
        Text("The ELF loader usually listens on 9021; some .bin loaders use 9020.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))

        // ---- PlayBeam Server card ----
        PearlCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Pearl.AccentSoft),
                        contentAlignment = Alignment.Center
                    ) { PIcon(R.drawable.ic_bolt, Pearl.Accent, 24.dp) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("PlayBeam Server", style = MaterialTheme.typography.titleMedium)
                        Text("Faster uploads · extracts ZIPs on the PS5", style = MaterialTheme.typography.bodySmall)
                    }
                    when (serverUp) {
                        true -> Chip("Running", Pearl.OkText, Pearl.OkSoft, Pearl.Ok)
                        false -> Chip("Off", Pearl.Muted, Pearl.Track)
                        null -> Chip("…", Pearl.Muted, Pearl.Track)
                    }
                }
                serverBusy?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    PearlProgress(null)
                }
                serverMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("❌")) Pearl.Error else Pearl.Muted)
                }
                if (serverUp == true) {
                    SecondaryButton("Stop server", R.drawable.ic_close, ::stopServer, Modifier.fillMaxWidth(),
                        enabled = serverBusy == null)
                } else {
                    PrimaryButton("Start on PS5", R.drawable.ic_bolt, ::startServer, enabled = serverBusy == null,
                        container = Pearl.Accent)
                }
            }
        }


        SectionLabel("Other payloads")
        SecondaryButton("Choose a payload and send", R.drawable.ic_file, { pick.launch(arrayOf("*/*")) },
            Modifier.fillMaxWidth(),
            enabled = sending == null)

        if (sending != null) {
            PearlCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sending $sending…", style = MaterialTheme.typography.titleSmall)
                    PearlProgress(sendProgress)
                }
            }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = if (statusOk) Pearl.OkText else Pearl.Error)
        }

        if (recent.isNotEmpty()) {
            SectionLabel("Recent")
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
                            Text("Send", fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(onClick = {
                            recent = recent.filter { it.uri != p.uri }
                            Store.savePayloads(ctx, recent)
                        }) { PIcon(R.drawable.ic_close, Pearl.Faint, 18.dp) }
                    }
                    if (idx < recent.lastIndex) HorizontalDivider(color = Pearl.Divider, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}
