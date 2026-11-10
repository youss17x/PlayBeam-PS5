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

    fun send(p: RecentPayload) {
        val host = state.host.trim()
        if (host.isBlank()) {
            statusOk = false; status = "Set your PS5 first in Settings (or use Find my PS5)."
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
            Text("Send any .elf / .bin payload to the PS5 loader. PlayBeam Server is in Settings.",
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

        PrimaryButton("Choose a payload and send", R.drawable.ic_file, { pick.launch(arrayOf("*/*")) },
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
