package com.youss17x.playbeam

import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {

    /** Files received from other apps through the Android share menu. */
    private val sharedUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.load(this)
        Pearl.load(this)
        applySystemBars(Pearl.isDark)
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            // status / navigation bar icons follow the theme
            LaunchedEffect(Pearl.isDark) { applySystemBars(Pearl.isDark) }
            // Arabic is shown right-to-left
            val dir = if (I18n.lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides dir) {
                PearlTheme {
                    Surface(Modifier.fillMaxSize(), color = Pearl.Bg) {
                        App(sharedUris.value, onSharedConsumed = { sharedUris.value = emptyList() })
                    }
                }
            }
        }
    }

    /** Light theme → dark bar icons, dark theme → light bar icons. */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT)
        else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) sharedUris.value = uris
    }
}

/** Settings saved on the phone + the current selection, shared by every tab. */
class AppState(private val ctx: Context) {
    private val prefs = Store.prefs(ctx)

    var host by mutableStateOf(prefs.getString("host", "")!!)
    var port by mutableStateOf(prefs.getString("port", "2121")!!)
    var remote by mutableStateOf(prefs.getString("remote", "/data/homebrew")!!)
    var resume by mutableStateOf(prefs.getBoolean("resume", true))
    var connections by mutableIntStateOf(prefs.getInt("connections", 2))
    var extractZips by mutableStateOf(prefs.getBoolean("extractZips", true))
    var consoles by mutableStateOf(Store.consoles(ctx))
        private set

    var selected by mutableStateOf<List<Uri>>(emptyList())
    var isTree by mutableStateOf(true)

    /** Folder the Files tab shows (kept while switching tabs). */
    var filesPath by mutableStateOf(remote.ifBlank { "/" })

    val portInt get() = port.toIntOrNull() ?: 2121

    val currentConsole: Console?
        get() = consoles.firstOrNull { it.host == host.trim() && it.port == portInt }

    fun updateConsoles(list: List<Console>) {
        consoles = list
        Store.saveConsoles(ctx, list)
    }

    fun save() = prefs.edit()
        .putString("host", host.trim())
        .putString("port", port)
        .putString("remote", remote.trim())
        .putBoolean("resume", resume)
        .putInt("connections", connections)
        .putBoolean("extractZips", extractZips)
        .apply()
}

enum class Tab(val icon: Int) {
    Upload(R.drawable.ic_upload),
    Files(R.drawable.ic_folder),
    Payload(R.drawable.ic_bolt),
    History(R.drawable.ic_history),
    Settings(R.drawable.ic_settings);

    val label: String
        get() = when (this) {
            Upload -> S.tabUpload
            Files -> S.tabFiles
            Payload -> S.tabPayload
            History -> S.tabHistory
            Settings -> S.tabSettings
        }
}

@Composable
fun App(sharedUris: List<Uri>, onSharedConsumed: () -> Unit) {
    val ctx = LocalContext.current
    val state = remember { AppState(ctx) }
    var tab by remember { mutableStateOf(Tab.Upload) }

    // look for a new version on GitHub when the app opens
    var update by remember { mutableStateOf<AppRelease?>(null) }
    LaunchedEffect(Unit) {
        if (Store.prefs(ctx).getBoolean("autoUpdate", true)) {
            update = runCatching { Updater.available(ctx) }.getOrNull()
        }
    }
    update?.let { UpdateDialog(it, onDismiss = { update = null }) }

    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            state.selected = sharedUris
            state.isTree = false
            tab = Tab.Upload
            onSharedConsumed()
        }
    }

    Scaffold(
        containerColor = Pearl.Bg,
        bottomBar = {
            Column {
                HorizontalDivider(color = Pearl.Line)
                NavigationBar(containerColor = Pearl.Card, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        val on = tab == t
                        NavigationBarItem(
                            selected = on,
                            onClick = { tab = t },
                            icon = { PIcon(t.icon, if (on) Pearl.Accent else Pearl.Muted) },
                            label = {
                                Text(t.label, fontSize = 11.sp, maxLines = 1,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = Pearl.Accent,
                                unselectedTextColor = Pearl.Muted,
                                indicatorColor = Pearl.AccentSoft,
                            ),
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Upload -> UploadScreen(state, goTo = { tab = it })
                Tab.Files -> FilesTab(state, goTo = { tab = it })
                Tab.Payload -> PayloadScreen(state)
                Tab.History -> HistoryScreen()
                Tab.Settings -> SettingsScreen(state)
            }
        }
    }
}

/** The PS5 file manager tab. */
@Composable
private fun FilesTab(state: AppState, goTo: (Tab) -> Unit) {
    if (state.host.isBlank()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ScreenTitle(S.tabFiles)
            PearlCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(S.noPs5Title, style = MaterialTheme.typography.titleMedium)
                    Text(S.noPs5Body, style = MaterialTheme.typography.bodySmall)
                    PrimaryButton(S.openSettings, R.drawable.ic_settings, { goTo(Tab.Settings) })
                }
            }
        }
        return
    }
    BrowserScreen(
        host = state.host.trim(),
        port = state.portInt,
        startPath = state.filesPath,
        onPathChange = { state.filesPath = it },
        pickLabel = S.uploadHere,
        onPick = { state.remote = it; state.save(); goTo(Tab.Upload) },
        onClose = null,
    )
}
