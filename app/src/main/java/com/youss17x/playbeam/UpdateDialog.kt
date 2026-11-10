package com.youss17x.playbeam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Shown when a newer PlayBeam is on GitHub: Update (download + install), Later, or Skip this version. */
@Composable
fun UpdateDialog(release: AppRelease, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }

    fun update() {
        if (!Updater.canInstall(ctx)) {
            message = S.allowInstall
            Updater.openInstallPermission(ctx)
            return
        }
        job = scope.launch {
            message = null
            progress = 0f
            try {
                val apk = Updater.download(ctx, release) { progress = it }
                Updater.install(ctx, apk)
                onDismiss()
            } catch (e: Exception) {
                message = "❌ ${e.message ?: S.updateCheckFailed}"
            }
            job = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (job == null) onDismiss() },
        containerColor = Pearl.Card,
        icon = { LogoMark(44.dp) },
        title = { Text(S.updateAvailable) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S.updateVersions(release.version, Updater.currentVersion(ctx)),
                    style = MaterialTheme.typography.bodyMedium)
                if (release.notes.isNotBlank()) {
                    Text(S.whatsNew.uppercase(), style = MaterialTheme.typography.labelSmall, color = Pearl.Faint)
                    Text(release.notes, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                }
                if (job != null) {
                    Text(S.downloadingUpdate, style = MaterialTheme.typography.bodyMedium)
                    PearlProgress(progress.takeIf { it > 0f })
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("❌")) Pearl.Error else Pearl.Muted)
                }
                if (job == null) {
                    TextButton(onClick = { Updater.skip(ctx, release.version); onDismiss() },
                        contentPadding = PaddingValues(0.dp)) {
                        Text(S.skipVersion, color = Pearl.Muted)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = ::update, enabled = job == null,
                colors = ButtonDefaults.buttonColors(containerColor = Pearl.Accent)) {
                Text(S.update, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = { job?.cancel(); onDismiss() }) { Text(if (job != null) S.cancel else S.later) }
        },
    )
}
