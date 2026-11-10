package com.youss17x.playbeam

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
        ScreenTitle(S.settings)

        // ---- language ----
        SectionLabel(S.secLanguage)
        PearlCard {
            Lang.entries.forEachIndexed { idx, l ->
                SettingsRow(l.nativeName, divider = idx < Lang.entries.lastIndex,
                    onClick = { I18n.set(ctx, l) },
                    trailing = {
                        if (I18n.lang == l) Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Pearl.Accent))
                    })
            }
        }

        Spacer(Modifier.height(4.dp))

        // ---- PS5 ----
        SectionLabel(S.secPs5)
        ConsoleEditor(state)

        // ---- PlayBeam Server ----
        Spacer(Modifier.height(4.dp))
        SectionLabel(S.secServer)
        ServerCard(state)

        // ---- transfers ----
        Spacer(Modifier.height(4.dp))
        SectionLabel(S.secTransfers)
        PearlCard {
            SettingsRow(S.resumeTitle,
                subtitle = S.resumeSub,
                trailing = { PearlSwitch(state.resume) { state.resume = it; state.save() } })
            SettingsRow(S.extractTitle,
                subtitle = S.extractSub,
                trailing = { PearlSwitch(state.extractZips) { state.extractZips = it; state.save() } })
            SettingsRow(S.connections, subtitle = S.connectionsSub, divider = false,
                trailing = { NumberPicker((1..4).toList(), state.connections) { state.connections = it; state.save() } })
        }

        // ---- downloads ----
        Spacer(Modifier.height(4.dp))
        SectionLabel(S.secDownloads)
        PearlCard {
            SettingsRow(S.downloadFolder, downloadTree?.let { treeName(it) } ?: S.notChosen,
                subtitle = S.downloadFolderSub,
                onClick = { pickDownloadFolder.launch(null) }, divider = false)
        }

        // ---- about ----
        Spacer(Modifier.height(4.dp))
        SectionLabel(S.secAbout)
        PearlCard {
            SettingsRow(S.version, version)
            SettingsRow(S.serverPort, "$SERVER_PORT")
            SettingsRow(S.sourceCode, "GitHub", divider = false, onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
            }, trailing = { PIcon(R.drawable.ic_link, Pearl.Faint, 16.dp) })
        }
        Text("PlayBeam by youss17x", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
}
