package com.youss17x.playbeam

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Date

@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val version by UploadState.historyVersion.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    val entries = remember(version, reload) { Store.history(ctx) }
    var confirmClear by remember { mutableStateOf(false) }
    val dateFmt = remember { DateFormat.getMediumDateFormat(ctx) }
    val timeFmt = remember { DateFormat.getTimeFormat(ctx) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
        }
        if (entries.isEmpty()) {
            Text("No uploads yet.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${if (e.ok) "✅" else "❌"} ${e.label}", style = MaterialTheme.typography.titleMedium)
                        val d = Date(e.time)
                        Text("${dateFmt.format(d)} ${timeFmt.format(d)}  •  ${formatDuration(e.durationMs)}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("${e.files} files  •  ${formatBytes(e.bytes)}  →  ${e.destination}",
                            style = MaterialTheme.typography.bodySmall)
                        if (!e.ok) Text(e.message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This removes the upload history from this phone. Files on the PS5 are not touched.") },
            confirmButton = {
                TextButton(onClick = { Store.clearHistory(ctx); reload++; confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
