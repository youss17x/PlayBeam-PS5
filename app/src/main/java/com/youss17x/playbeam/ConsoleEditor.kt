package com.youss17x.playbeam

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Choosing the PS5: saved consoles, Find my PS5, manual IP / FTP port.
 * Used in Settings and in the Console sheet of the Upload tab.
 * [onPicked] is called after a console was chosen from a list (the sheet closes then).
 */
@Composable
fun ConsoleEditor(state: AppState, onPicked: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var found by remember { mutableStateOf<List<FoundDevice>?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val consoles = state.consoles

    fun use(host: String, port: Int) {
        state.host = host; state.port = port.toString(); state.save()
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (consoles.isNotEmpty()) {
            PearlCard {
                consoles.forEachIndexed { idx, c ->
                    val on = c.host == state.host.trim() && c.port == state.portInt
                    Row(
                        Modifier.fillMaxWidth().clickable { use(c.host, c.port); onPicked?.invoke() }
                            .heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PIcon(R.drawable.ic_console, if (on) Pearl.Accent else Pearl.Muted)
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall,
                                color = if (on) Pearl.Accent else Pearl.Ink)
                            Text("${c.host}:${c.port}", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { state.updateConsoles(consoles.filter { it != c }) }) {
                            PIcon(R.drawable.ic_close, Pearl.Faint, 18.dp)
                        }
                    }
                    if (idx < consoles.lastIndex) HorizontalDivider(color = Pearl.Divider,
                        modifier = Modifier.padding(start = 16.dp))
                }
            }
        }

        SecondaryButton(
            if (scanning) "Searching… ${(scanProgress * 100).toInt()}%" else "Find my PS5",
            R.drawable.ic_search,
            onClick = {
                scope.launch {
                    scanning = true; found = null; scanError = null; scanProgress = 0f
                    try {
                        found = PS5Discovery.scan(ctx) { scanProgress = it }
                    } catch (e: Exception) {
                        scanError = e.message ?: "Search failed"
                    }
                    scanning = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !scanning,
        )
        if (scanning) PearlProgress(scanProgress)
        scanError?.let { Text(it, color = Pearl.Error, style = MaterialTheme.typography.bodyMedium) }
        found?.let { list ->
            if (list.isEmpty()) {
                Text("No PS5 found. Make sure PlayBeam Server or an FTP server is running on your PS5 and both devices are on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                PearlCard {
                    list.forEachIndexed { idx, d ->
                        val what = listOfNotNull(
                            if (d.server) "PlayBeam Server" else null,
                            d.ftpPort?.let { "FTP $it" },
                        ).joinToString(" · ")
                        SettingsRow(d.host, what, subtitle = d.banner.ifBlank { null },
                            onClick = { use(d.host, d.ftpPort ?: state.portInt); found = null; onPicked?.invoke() },
                            divider = idx < list.lastIndex)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.host, { state.host = it; state.save() }, label = { Text("PS5 IP") },
                singleLine = true, modifier = Modifier.weight(2f), shape = RoundedCornerShape(14.dp),
                colors = pearlFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(state.port, { state.port = it.filter(Char::isDigit).take(5); state.save() },
                label = { Text("FTP port") },
                singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                colors = pearlFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Text("PlayBeam Server always uses port $SERVER_PORT automatically. The FTP port is only used when the server isn't running (usually 2121).",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
        SecondaryButton("Save this PS5", R.drawable.ic_plus, { saving = true }, Modifier.fillMaxWidth(),
            enabled = state.host.isNotBlank())
    }

    if (saving) {
        var name by remember { mutableStateOf(if (consoles.isEmpty()) "My PS5" else "PS5 ${consoles.size + 1}") }
        AlertDialog(
            onDismissRequest = { saving = false },
            containerColor = Pearl.Card,
            title = { Text("Save this PS5") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${state.host.trim()}:${state.port}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") },
                        colors = pearlFieldColors(), shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    state.save()
                    val c = Console(name.trim(), state.host.trim(), state.portInt)
                    state.updateConsoles(
                        consoles.filter { it.name != c.name && !(it.host == c.host && it.port == c.port) } + c)
                    saving = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }
}

/** Bottom sheet around [ConsoleEditor], opened from the Upload tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleSheet(state: AppState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = { state.save(); onDismiss() }, containerColor = Pearl.Bg) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Console", style = MaterialTheme.typography.headlineSmall)
            ConsoleEditor(state, onPicked = onDismiss)
            PrimaryButton("Done", null, { state.save(); onDismiss() })
        }
    }
}
