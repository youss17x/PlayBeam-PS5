package com.youss17x.playbeam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { UploadScreen() }
            }
        }
    }
}

private val PRESETS = listOf("/data/homebrew", "/data/etaHEN/games", "/mnt/usb0")

@Composable
fun UploadScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var host by remember { mutableStateOf(prefs.getString("host", "192.168.1.185")!!) }
    var port by remember { mutableStateOf(prefs.getString("port", "2121")!!) }
    var remote by remember { mutableStateOf(prefs.getString("remote", "/data/homebrew")!!) }

    var selected by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isTree by remember { mutableStateOf(false) }
    val progress by UploadState.progress.collectAsState()

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { selected = listOf(uri); isTree = true }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { selected = uris; isTree = false }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun startUpload() {
        prefs.edit().putString("host", host).putString("port", port).putString("remote", remote).apply()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val i = Intent(ctx, UploadService::class.java)
            .putExtra(UploadService.EXTRA_HOST, host.trim())
            .putExtra(UploadService.EXTRA_PORT, port.toIntOrNull() ?: 2121)
            .putExtra(UploadService.EXTRA_REMOTE, remote.trim())
            .putExtra(UploadService.EXTRA_IS_TREE, isTree)
            .putParcelableArrayListExtra(UploadService.EXTRA_URIS, ArrayList(selected))
        ContextCompat.startForegroundService(ctx, i)
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("PlayBeam", style = MaterialTheme.typography.headlineMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { host = it }, label = { Text("PS5 IP") },
                singleLine = true, modifier = Modifier.weight(2f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }

        OutlinedTextField(remote, { remote = it }, label = { Text("Destination path on PS5") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { p ->
                FilterChip(selected = remote == p, onClick = { remote = p }, label = { Text(p.substringAfterLast('/')) })
            }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.weight(1f)) { Text("📁 Folder") }
            OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("📄 Files") }
        }
        Text(
            when {
                selected.isEmpty() -> "Nothing selected yet"
                isTree -> "Folder: ${Uri.decode(selected[0].lastPathSegment ?: "").substringAfterLast(':')}"
                else -> "${selected.size} file(s) selected"
            },
            style = MaterialTheme.typography.bodyMedium
        )

        if (progress.running) {
            Button(onClick = {
                ctx.startService(Intent(ctx, UploadService::class.java).setAction(UploadService.ACTION_STOP))
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()) { Text("Stop") }
        } else {
            Button(onClick = ::startUpload, enabled = selected.isNotEmpty() && host.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text("⬆️ Upload to PS5") }
        }

        if (progress.running || progress.message.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (progress.running) {
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress.fraction * 100).toInt()}%  •  ${progress.doneFiles}/${progress.totalFiles} files")
                        Text("${formatBytes(progress.doneBytes)} / ${formatBytes(progress.totalBytes)}  •  ${formatBytes(progress.speedBytesPerSec)}/s")
                        if (progress.currentFile.isNotEmpty())
                            Text(progress.currentFile, style = MaterialTheme.typography.bodySmall)
                    }
                    if (progress.message.isNotEmpty()) Text(progress.message)
                }
            }
        }
    }
}
