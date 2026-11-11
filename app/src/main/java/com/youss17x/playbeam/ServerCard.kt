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

    // PIN: is the server protected, and may this phone use it
    var pinProtected by remember { mutableStateOf(false) }
    var authorized by remember { mutableStateOf(true) }
    var pinBusy by remember { mutableStateOf(false) }
    var pinMsg by remember { mutableStateOf<String?>(null) }
    var pinText by remember { mutableStateOf("") }

    suspend fun refresh() {
        val st = ServerClient.status(state.host.trim())
        serverUp = st != null
        pinProtected = st?.first ?: false
        authorized = st?.second ?: true
    }

    LaunchedEffect(state.host) {
        while (true) {
            if (serverBusy == null && !pinBusy) refresh()
            delay(8_000)
        }
    }

    fun pinAction(newPin: String?, unlock: Boolean = false) {
        val host = state.host.trim()
        if (newPin != null && newPin.isNotEmpty() && (newPin.length !in 4..8 || !newPin.all(Char::isDigit))) {
            pinMsg = S.pinInvalid; return
        }
        scope.launch {
            pinBusy = true; pinMsg = null
            pinMsg = try {
                withContext(Dispatchers.IO) {
                    ServerClient(host).use { c ->
                        if (unlock) {
                            if (c.auth(newPin!!)) { Pins.set(host, newPin); S.pinUnlocked } else S.pinWrongShort
                        } else {
                            c.setPin(newPin ?: "")
                            Pins.set(host, newPin)
                            if (newPin.isNullOrEmpty()) S.pinRemoved else S.pinSet
                        }
                    }
                }
            } catch (e: PinError) {
                "❌ " + e.message
            } catch (e: ServerError) {
                if (e.message == "unknown command") S.pinUpdateServer else "❌ " + e.message
            } catch (e: Exception) {
                "❌ " + (e.message ?: S.sendFailed)
            }
            pinText = ""
            refresh()
            pinBusy = false
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

    // PlayBeam tile on the PS5 home screen (opens the server page in the PS5 browser).
    // Hidden until the installer works on real consoles.
    if (SHOW_HOME_TILE) {
    var tileMsg by remember { mutableStateOf<String?>(null) }
    var tileBusy by remember { mutableStateOf(false) }
    SecondaryButton(S.addTile, R.drawable.ic_plus, {
        val host = state.host.trim()
        if (host.isBlank()) { tileMsg = S.setPs5InSettings; return@SecondaryButton }
        scope.launch {
            tileBusy = true; tileMsg = null
            tileMsg = try {
                val out = PayloadSender.sendTileInstaller(ctx, host, loaderPort)
                S.tileSent + if (out.isNotBlank()) "\n\n" + out.takeLast(1500) else ""
            } catch (e: Exception) {
                "❌ " + S.loaderNotRunning(e.message ?: S.sendFailed, loaderPort)
            }
            tileBusy = false
        }
    }, Modifier.fillMaxWidth(), enabled = !tileBusy)
    tileMsg?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp),
            color = if (it.startsWith("❌")) Pearl.Error else Pearl.Muted)
    }
    }

    if (serverUp == true) {
        Text(S.webPageNote(state.host.trim()),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
        PearlCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(S.pinTitle, style = MaterialTheme.typography.titleMedium)
                        Text(S.pinSub, style = MaterialTheme.typography.bodySmall)
                    }
                    if (pinProtected) Chip(S.pinOn, Pearl.OkText, Pearl.OkSoft, Pearl.Ok)
                    else Chip(S.off, Pearl.Muted, Pearl.Track)
                }
                if (pinProtected && !authorized) {
                    Text(S.pinLocked, style = MaterialTheme.typography.bodyMedium, color = Pearl.Error)
                }
                OutlinedTextField(
                    pinText, { pinText = it.filter(Char::isDigit).take(8) },
                    label = { Text(S.pinField) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp), colors = pearlFieldColors(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                )
                if (pinProtected && !authorized) {
                    PrimaryButton(S.unlock, R.drawable.ic_bolt, { pinAction(pinText, unlock = true) },
                        enabled = !pinBusy && pinText.length >= 4, container = Pearl.Accent)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(S.setPin, R.drawable.ic_edit, { pinAction(pinText) }, Modifier.weight(1f),
                            enabled = !pinBusy && pinText.length >= 4)
                        if (pinProtected) {
                            SecondaryButton(S.removePin, R.drawable.ic_close, { pinAction("") }, Modifier.weight(1f),
                                enabled = !pinBusy)
                        }
                    }
                }
                if (pinBusy) PearlProgress(null)
                pinMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("❌")) Pearl.Error else Pearl.OkText)
                }
                Text(S.pinForgot, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The home screen tile installer is not reliable yet. */
private const val SHOW_HOME_TILE = false
