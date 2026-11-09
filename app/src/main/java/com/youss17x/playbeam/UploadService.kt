package com.youss17x.playbeam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service: keeps uploading when the screen is off or the app is in the background.
 */
class UploadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        intent ?: return START_NOT_STICKY

        val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
        val port = intent.getIntExtra(EXTRA_PORT, 2121)
        val remote = intent.getStringExtra(EXTRA_REMOTE) ?: "/data"
        val isTree = intent.getBooleanExtra(EXTRA_IS_TREE, false)
        val uris = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java)
            ?: return START_NOT_STICKY

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("جارٍ التحضير…", 0, true),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        acquireLocks()

        job = scope.launch { runUpload(host, port, remote, isTree, uris) }
        return START_NOT_STICKY
    }

    private suspend fun runUpload(host: String, port: Int, remote: String, isTree: Boolean, uris: List<Uri>) {
        val state = UploadState.progress
        state.value = Progress(running = true, message = "جارٍ فحص الملفات…")
        val uploader = FtpUploader(this, host, port, remote)
        try {
            val scanner = FileScanner(this)
            val entries = if (isTree) uris.flatMap { scanner.scanTree(it) } else scanner.scanFiles(uris)
            val files = entries.filter { !it.isDir }
            val totalBytes = files.sumOf { it.size }
            state.value = state.value.copy(totalFiles = files.size, totalBytes = totalBytes,
                message = "جارٍ الاتصال بـ $host:$port…")

            uploader.connect()

            val startTime = SystemClock.elapsedRealtime()
            var done = 0L
            var lastUi = 0L
            uploader.upload(
                entries,
                onFileStart = { e -> state.value = state.value.copy(currentFile = e.relativePath, message = "") },
                onBytes = { n ->
                    done += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi > 300) {
                        lastUi = now
                        val secs = ((now - startTime) / 1000.0).coerceAtLeast(0.001)
                        val p = state.value.copy(doneBytes = done, speedBytesPerSec = (done / secs).toLong())
                        state.value = p
                        notify("${p.currentFile}\n${formatBytes(p.speedBytesPerSec)}/s",
                            (p.fraction * 100).toInt(), false)
                    }
                },
                onFileDone = { state.value = state.value.copy(doneFiles = state.value.doneFiles + 1, doneBytes = done) },
            )
            state.value = state.value.copy(running = false, doneBytes = totalBytes,
                message = "✅ اكتمل الرفع: ${files.size} ملف (${formatBytes(totalBytes)})")
        } catch (e: CancellationException) {
            state.value = state.value.copy(running = false, message = "⏹ تم إيقاف الرفع")
        } catch (e: Exception) {
            state.value = state.value.copy(running = false, message = "❌ خطأ: ${e.message}")
        } finally {
            uploader.disconnect()
            releaseLocks()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "playbeam:wifi").apply { acquire() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "playbeam:cpu").apply { acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "رفع الملفات", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String, percent: Int, indeterminate: Boolean): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, UploadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("الرفع إلى PS5 — $percent%")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "إيقاف", stop)
            .build()
    }

    private fun notify(text: String, percent: Int, indeterminate: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, percent, indeterminate))
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_REMOTE = "remote"
        const val EXTRA_URIS = "uris"
        const val EXTRA_IS_TREE = "isTree"
        private const val CHANNEL = "upload"
        private const val NOTIF_ID = 1
    }
}
