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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Sends an .elf / .bin payload to the PS5 payload loader over a raw TCP socket. */
object PayloadSender {
    suspend fun send(ctx: Context, uri: Uri, host: String, port: Int, onProgress: (Float) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Can't read the payload file")
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
                status = "❌ ${e.message ?: "Send failed"}. Is the payload loader running on the PS5?"
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Send payload", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sends an .elf or .bin payload to the PS5 loader — for example to start the FTP server before uploading.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PS5: ${state.host.ifBlank { "not set" }}",
                Modifier.weight(2f), style = MaterialTheme.typography.bodyLarge
            )
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Loader port") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Text("The ELF loader usually listens on 9021; some .bin loaders use 9020.",
            style = MaterialTheme.typography.bodySmall)

        Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = sending == null, modifier = Modifier.fillMaxWidth()) {
            Text("🚀 Choose payload and send")
        }

        if (sending != null) {
            Text("Sending $sending…")
            LinearProgressIndicator(progress = { sendProgress }, modifier = Modifier.fillMaxWidth())
        }
        status?.let {
            Text(it, color = if (statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }

        if (recent.isNotEmpty()) {
            HorizontalDivider()
            Text("Recent payloads", style = MaterialTheme.typography.titleMedium)
            recent.forEach { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { send(p) }, enabled = sending == null) { Text("Send") }
                        TextButton(onClick = {
                            recent = recent.filter { it.uri != p.uri }
                            Store.savePayloads(ctx, recent)
                        }) { Text("✕") }
                    }
                }
            }
        }
    }
}
