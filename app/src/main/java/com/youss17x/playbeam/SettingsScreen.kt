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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch


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

    val scope = rememberCoroutineScope()
    var autoUpdate by remember { mutableStateOf(prefs.getBoolean("autoUpdate", true)) }
    var checking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<AppRelease?>(null) }
    found?.let { UpdateDialog(it, onDismiss = { found = null }) }

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

        // ---- appearance ----
        SectionLabel(S.secAppearance)
        PearlCard {
            val modes = listOf(ThemeMode.SYSTEM to S.themeSystem, ThemeMode.LIGHT to S.themeLight, ThemeMode.DARK to S.themeDark)
            modes.forEachIndexed { idx, (m, label) ->
                SettingsRow(label, divider = idx < modes.lastIndex,
                    onClick = { Pearl.set(ctx, m) },
                    trailing = {
                        if (Pearl.mode == m) Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Pearl.Accent))
                    })
            }
        }

        Spacer(Modifier.height(4.dp))
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

        // ---- PS5 info (model, sensors, drives) ----
        Spacer(Modifier.height(4.dp))
        SectionLabel(S.ps5Info)
        Ps5InfoCard(state)

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
        AboutCard(version)
        PearlCard {
            SettingsRow(S.checkUpdates, updateStatus, onClick = {
                if (checking) return@SettingsRow
                scope.launch {
                    checking = true
                    updateStatus = S.checking
                    updateStatus = try {
                        val r = Updater.available(ctx, ignoreSkipped = true)
                        if (r != null) { found = r; null } else S.upToDate
                    } catch (e: Exception) {
                        S.updateCheckFailed
                    }
                    checking = false
                }
            })
            SettingsRow(S.autoCheck, subtitle = S.autoCheckSub, trailing = {
                PearlSwitch(autoUpdate) { autoUpdate = it; prefs.edit().putBoolean("autoUpdate", it).apply() }
            })
            SettingsRow(S.serverPort, "$SERVER_PORT", divider = false)
        }
    }
}

/** App identity and the author's links, like an "About" page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutCard(version: String) {
    val ctx = LocalContext.current
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    PearlCard {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LogoMark(72.dp)
            Text("PlayBeam", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("v$version", Pearl.Ink, Pearl.Track)
                Chip(S.openSource, Pearl.OkText, Pearl.OkSoft)
            }
            Text(S.appTagline, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Links.all.filter { it.second.isNotBlank() }.forEach { (label, url) ->
                    SecondaryButton(label, R.drawable.ic_link, { open(url) })
                }
                SecondaryButton("Discord", R.drawable.ic_link, { open(Links.DISCORD) })
            }
            Text(S.madeBy, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The author's pages. Leave a link empty to hide its button. */
object Links {
    const val GITHUB = "https://github.com/youss17x"
    const val X = "https://x.com/frank_casle"
    const val FACEBOOK = "https://www.facebook.com/share/1c4Xf3d7oG/"
    const val DISCORD = "https://discord.com/users/477911909719474206"

    val all = listOf("GitHub" to GITHUB, "X" to X, "Facebook" to FACEBOOK)
}
