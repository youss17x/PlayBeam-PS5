package com.youss17x.playbeam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** PlayBeam Server: status, Start (sends the bundled payload to the ELF loader) and Stop. */
@Composable
fun ServerCard(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaderPort = remember { Store.prefs(ctx).getString("payloadPort", "9021")!!.toIntOrNull() ?: 9021 }

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
        if (host.isBlank()) { serverMsg = S.setPs5InSettings; return }
        scope.launch {
            serverBusy = S.sendingServer; serverMsg = null
            try {
                PayloadSender.sendBundledServer(ctx, host, loaderPort) { }
                serverBusy = S.starting
                var up = false
                repeat(8) { if (!up) { delay(1_000); up = ServerClient.isRunning(host) } }
                serverUp = up
                serverMsg = if (up) S.serverRunningMsg else S.serverNoAnswer
            } catch (e: Exception) {
                serverMsg = if (!onLocalNetwork(ctx)) "❌ ${friendlyError(ctx, host, e)}"
                    else "❌ " + S.loaderNotRunning(e.message ?: S.sendFailed, loaderPort)
            }
            serverBusy = null
        }
    }

    fun stopServer() {
        val host = state.host.trim()
        scope.launch {
            serverBusy = S.stopping
            runCatching { withContext(Dispatchers.IO) { ServerClient(host).use { it.shutdownServer() } } }
            delay(500)
            serverUp = ServerClient.isRunning(host)
            serverMsg = if (serverUp == false) S.serverStopped else null
            serverBusy = null
        }
    }

    PearlCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Pearl.AccentSoft),
                    contentAlignment = Alignment.Center
                ) { PIcon(R.drawable.ic_bolt, Pearl.Accent, 24.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("PlayBeam Server", style = MaterialTheme.typography.titleMedium)
                    Text(S.serverCardSub, style = MaterialTheme.typography.bodySmall)
                }
                when (serverUp) {
                    true -> Chip(S.running, Pearl.OkText, Pearl.OkSoft, Pearl.Ok)
                    false -> Chip(S.off, Pearl.Muted, Pearl.Track)
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
                SecondaryButton(S.stopServer, R.drawable.ic_close, ::stopServer, Modifier.fillMaxWidth(),
                    enabled = serverBusy == null)
            } else {
                PrimaryButton(S.startOnPs5, R.drawable.ic_bolt, ::startServer, enabled = serverBusy == null,
                    container = Pearl.Accent)
            }
        }
    }
    Text(S.serverLoaderNote(loaderPort),
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
}
