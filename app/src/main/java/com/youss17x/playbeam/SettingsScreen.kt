package com.youss17x.playbeam

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val REPO_URL = "https://github.com/youss17x/PlayBeam"

@Composable
fun SettingsScreen(state: AppState) {
    val ctx = LocalContext.current
    val prefs = remember { Store.prefs(ctx) }
    var downloadTree by remember { mutableStateOf(prefs.getString("downloadTree", null)?.let(Uri::parse)) }
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    val pickDownloadFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            prefs.edit().putString("downloadTree", uri.toString()).apply()
            downloadTree = uri
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle("Settings")

        // ---- PS5 ----
        SectionLabel("PS5")
        ConsoleEditor(state)

        // ---- PlayBeam Server ----
        Spacer(Modifier.height(4.dp))
        SectionLabel("PlayBeam Server")
        ServerCard(state)

        // ---- transfers ----
        Spacer(Modifier.height(4.dp))
        SectionLabel("Transfers")
        PearlCard {
            SettingsRow("Resume unfinished files",
                subtitle = "Skip files already on the PS5, continue partial ones",
                trailing = { PearlSwitch(state.resume) { state.resume = it; state.save() } })
            SettingsRow("Extract ZIPs on PS5",
                subtitle = "Unzip uploaded .zip files, then delete the .zip (needs PlayBeam Server)",
                trailing = { PearlSwitch(state.extractZips) { state.extractZips = it; state.save() } })
            SettingsRow("Connections", subtitle = "More is faster for many small files", divider = false,
                trailing = { NumberPicker((1..4).toList(), state.connections) { state.connections = it; state.save() } })
        }

        // ---- downloads ----
        Spacer(Modifier.height(4.dp))
        SectionLabel("Downloads")
        PearlCard {
            SettingsRow("Phone download folder", downloadTree?.let { treeName(it) } ?: "Not chosen",
                subtitle = "Where files downloaded from the PS5 are saved",
                onClick = { pickDownloadFolder.launch(null) }, divider = false)
        }

        // ---- about ----
        Spacer(Modifier.height(4.dp))
        SectionLabel("About")
        PearlCard {
            SettingsRow("Version", version)
            SettingsRow("PlayBeam Server port", "$SERVER_PORT")
            SettingsRow("Source code", "GitHub", divider = false, onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
            }, trailing = { PIcon(R.drawable.ic_link, Pearl.Faint, 16.dp) })
        }
        Text("PlayBeam by youss17x", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
}
