package com.youss17x.playbeam

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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


/** The pages inside Settings. The main screen lists them; each opens on its own. */
private enum class SettingsPage { LANGUAGE, APPEARANCE, PS5, SERVER, INFO, TRANSFERS, DOWNLOADS, UPDATES, ABOUT }

@Composable
fun SettingsScreen(state: AppState) {
    val ctx = LocalContext.current
    val prefs = remember { Store.prefs(ctx) }
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    var downloadTree by remember { mutableStateOf(prefs.getString("downloadTree", null)?.let(Uri::parse)) }
    val version = remember { Updater.currentVersion(ctx) }

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

    fun checkNow() {
        if (checking) return
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
    }

    // ---------------- main list ----------------
    val current = page
    if (current == null) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ScreenTitle(S.settings)
            val themeName = when (Pearl.mode) {
                ThemeMode.SYSTEM -> S.themeSystem
                ThemeMode.LIGHT -> S.themeLight
                ThemeMode.DARK -> S.themeDark
            }
            PearlCard {
                SettingsRow(S.secLanguage, I18n.lang.nativeName, icon = R.drawable.ic_globe,
                    onClick = { page = SettingsPage.LANGUAGE })
                SettingsRow(S.secAppearance, themeName, icon = R.drawable.ic_theme, divider = false,
                    onClick = { page = SettingsPage.APPEARANCE })
            }
            PearlCard {
                SettingsRow(S.secPs5, state.currentConsole?.name ?: state.host.trim().ifBlank { S.notSet },
                    icon = R.drawable.ic_console, onClick = { page = SettingsPage.PS5 })
                SettingsRow(S.secServer, icon = R.drawable.ic_bolt, onClick = { page = SettingsPage.SERVER })
                SettingsRow(S.ps5Info, icon = R.drawable.ic_info, divider = false,
                    onClick = { page = SettingsPage.INFO })
            }
            PearlCard {
                SettingsRow(S.secTransfers, S.optConn(state.connections), icon = R.drawable.ic_upload,
                    onClick = { page = SettingsPage.TRANSFERS })
                SettingsRow(S.secDownloads, downloadTree?.let { treeName(it) } ?: S.notChosen,
                    icon = R.drawable.ic_download, divider = false, onClick = { page = SettingsPage.DOWNLOADS })
            }
            PearlCard {
                SettingsRow(S.updates, "v$version", icon = R.drawable.ic_refresh,
                    onClick = { page = SettingsPage.UPDATES })
                SettingsRow(S.secAbout, icon = R.drawable.ic_stat_playbeam, divider = false,
                    onClick = { page = SettingsPage.ABOUT })
            }
        }
        return
    }

    // ---------------- one page ----------------
    BackHandler { page = null }
    val title = when (current) {
        SettingsPage.LANGUAGE -> S.secLanguage
        SettingsPage.APPEARANCE -> S.secAppearance
        SettingsPage.PS5 -> S.secPs5
        SettingsPage.SERVER -> S.secServer
        SettingsPage.INFO -> S.ps5Info
        SettingsPage.TRANSFERS -> S.secTransfers
        SettingsPage.DOWNLOADS -> S.secDownloads
        SettingsPage.UPDATES -> S.updates
        SettingsPage.ABOUT -> S.secAbout
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { page = null }) { PIcon(R.drawable.ic_back, Pearl.Ink) }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }

        when (current) {
            SettingsPage.LANGUAGE -> PearlCard {
                Lang.entries.forEachIndexed { idx, l ->
                    SettingsRow(l.nativeName, divider = idx < Lang.entries.lastIndex,
                        onClick = { I18n.set(ctx, l) },
                        trailing = { SelectedDot(I18n.lang == l) })
                }
            }

            SettingsPage.APPEARANCE -> PearlCard {
                val modes = listOf(ThemeMode.SYSTEM to S.themeSystem, ThemeMode.LIGHT to S.themeLight, ThemeMode.DARK to S.themeDark)
                modes.forEachIndexed { idx, (m, label) ->
                    SettingsRow(label, divider = idx < modes.lastIndex,
                        onClick = { Pearl.set(ctx, m) },
                        trailing = { SelectedDot(Pearl.mode == m) })
                }
            }

            SettingsPage.PS5 -> ConsoleEditor(state)

            SettingsPage.SERVER -> ServerCard(state)

            SettingsPage.INFO -> Ps5InfoCard(state)

            SettingsPage.TRANSFERS -> PearlCard {
                SettingsRow(S.resumeTitle,
                    subtitle = S.resumeSub,
                    trailing = { PearlSwitch(state.resume) { state.resume = it; state.save() } })
                SettingsRow(S.extractTitle,
                    subtitle = S.extractSub,
                    trailing = { PearlSwitch(state.extractZips) { state.extractZips = it; state.save() } })
                SettingsRow(S.connections, subtitle = S.connectionsSub, divider = false,
                    trailing = { NumberPicker((1..4).toList(), state.connections) { state.connections = it; state.save() } })
            }

            SettingsPage.DOWNLOADS -> PearlCard {
                SettingsRow(S.downloadFolder, downloadTree?.let { treeName(it) } ?: S.notChosen,
                    subtitle = S.downloadFolderSub,
                    onClick = { pickDownloadFolder.launch(null) }, divider = false)
            }

            SettingsPage.UPDATES -> PearlCard {
                SettingsRow(S.version, "v$version")
                SettingsRow(S.checkUpdates, updateStatus, onClick = ::checkNow)
                SettingsRow(S.autoCheck, subtitle = S.autoCheckSub, divider = false, trailing = {
                    PearlSwitch(autoUpdate) { autoUpdate = it; prefs.edit().putBoolean("autoUpdate", it).apply() }
                })
            }

            SettingsPage.ABOUT -> {
                AboutCard(version)
                PearlCard {
                    SettingsRow(S.serverPort, "$SERVER_PORT", divider = false)
                }
            }
        }
    }
}

@Composable
private fun SelectedDot(on: Boolean) {
    if (on) Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Pearl.Accent))
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
            Text(APP_NAME, style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("v$version", Pearl.Ink, Pearl.Track)
                Chip("GPL-3.0", Pearl.OkText, Pearl.OkSoft)
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
                SecondaryButton(S.sourceCode, R.drawable.ic_file, { open(Links.SOURCE) })
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
    const val SOURCE = "https://github.com/youss17x/PlayBeam"

    val all = listOf("GitHub" to GITHUB, "X" to X, "Facebook" to FACEBOOK)
}
