package com.youss17x.playbeam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class Ps5Info(
    val info: Map<String, String>,
    val drives: List<Pair<String, Pair<Long, Long>>>,
)

/** "13.60" from "… r229358/releases/13.60 Jul 17 2026 …" */
private fun firmwareOf(fw: String?): String? =
    fw?.let { Regex("releases/([0-9.]+)").find(it)?.groupValues?.get(1) ?: it.take(40) }

private fun driveLabel(root: String): String = when {
    root == "/data" -> S.internalStorage
    root.startsWith("/mnt/usb") -> "USB " + (root.removePrefix("/mnt/usb").toIntOrNull()?.plus(1) ?: "")
    else -> S.extended
}

/** PS5 model, firmware, live temperatures and drives — needs PlayBeam Server. Refreshes every 5 s. */
@Composable
fun Ps5InfoCard(state: AppState) {
    var data by remember { mutableStateOf<Ps5Info?>(null) }
    var offline by remember { mutableStateOf(false) }

    LaunchedEffect(state.host) {
        val host = state.host.trim()
        while (true) {
            val r = if (host.isBlank()) null else withContext(Dispatchers.IO) {
                runCatching {
                    ServerClient(host, connectTimeoutMs = 2_000).use { Ps5Info(it.info(), it.drives()) }
                }.getOrNull()
            }
            offline = r == null
            if (r != null) data = r
            delay(5_000)
        }
    }

    val d = data
    if (d == null || offline) {
        PearlCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PIcon(R.drawable.ic_info, Pearl.Muted)
                Text(if (d == null && !offline) S.checking else S.infoNeedsServer,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
        return
    }

    PearlCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // ---- model & firmware ----
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Pearl.AccentSoft),
                    contentAlignment = Alignment.Center
                ) { PIcon(R.drawable.ic_console, Pearl.Accent, 26.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(d.info["model"] ?: "PS5", style = MaterialTheme.typography.titleMedium)
                    firmwareOf(d.info["fw"])?.let {
                        Text(S.firmware(it), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Chip(state.host.trim(), Pearl.OkText, Pearl.OkSoft, Pearl.Ok)
            }

            // ---- live sensors ----
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(S.liveSensors, style = MaterialTheme.typography.titleSmall)
                Text(S.autoRefresh, style = MaterialTheme.typography.bodySmall)
            }
            val tiles = listOfNotNull(
                d.info["cpu_temp"]?.let { Triple(S.cpuTemp, "$it°C", it.toIntOrNull()) },
                d.info["soc_temp"]?.let { Triple(S.socTemp, "$it°C", it.toIntOrNull()) },
                d.info["cpu_mhz"]?.let { Triple(S.cpuFreq, "$it MHz", null) },
                d.info["uptime"]?.toLongOrNull()?.let { Triple(S.uptime, formatDuration(it * 1000), null) },
            )
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, value, temp) ->
                        SensorTile(label, value, temp, Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // ---- storage ----
            if (d.drives.isNotEmpty()) {
                Text(S.storage, style = MaterialTheme.typography.titleSmall)
                d.drives.forEach { (root, space) ->
                    val (total, free) = space
                    val used = (total - free).coerceAtLeast(0)
                    val frac = if (total > 0) used.toFloat() / total else 0f
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PIcon(if (root == "/data") R.drawable.ic_console else R.drawable.ic_drive,
                                Pearl.Muted, 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(driveLabel(root), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(S.free(formatBytes(free)), style = MaterialTheme.typography.bodySmall,
                                color = if (frac > 0.9f) Pearl.Error else Pearl.Muted)
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(Pearl.Track)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(50))
                                .background(if (frac > 0.9f) Pearl.Error else Pearl.Accent))
                        }
                        Text(S.usedOf(formatBytes(used), formatBytes(total)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SensorTile(label: String, value: String, temp: Int?, modifier: Modifier) {
    val hot = temp != null && temp >= 80
    val warm = temp != null && temp >= 65
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Pearl.Bg) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Pearl.Faint)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = when {
                    hot -> Pearl.Error
                    warm -> Color(0xFFE08A00)
                    else -> Pearl.Ink
                })
        }
    }
}
