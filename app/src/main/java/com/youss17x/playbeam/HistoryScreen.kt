package com.youss17x.playbeam

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LogoMark()
                Text("PlayBeam", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                ScreenTitle("History", Modifier.weight(1f))
                if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        }
        if (entries.isEmpty()) {
            item {
                PearlCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No uploads yet", style = MaterialTheme.typography.titleMedium)
                        Text("Everything you send to the PS5 will be listed here.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        items(entries) { e ->
            PearlCard {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (e.ok) Pearl.OkSoft else Pearl.ErrorSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        PIcon(if (e.ok) R.drawable.ic_upload else R.drawable.ic_close,
                            if (e.ok) Pearl.Ok else Pearl.Error, 20.dp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(e.label, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        val d = Date(e.time)
                        Text("${dateFmt.format(d)} · ${timeFmt.format(d)} · ${formatDuration(e.durationMs)}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("${e.files} files · ${formatBytes(e.bytes)} → ${e.destination}",
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!e.ok) Text(e.message, style = MaterialTheme.typography.bodySmall, color = Pearl.Error)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Pearl.Card,
            title = { Text("Clear history?") },
            text = { Text("This removes the upload history from this phone. Files on the PS5 are not touched.") },
            confirmButton = {
                TextButton(onClick = { Store.clearHistory(ctx); reload++; confirmClear = false }) {
                    Text("Clear", color = Pearl.Error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
