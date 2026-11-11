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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Foreground service with an upload queue: keeps uploading when the screen is off
 * or the app is in the background. Queue bookkeeping runs on the main thread only.
 */
class UploadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pending = ArrayDeque<UploadJob>()
    private var looping = false
    private var currentJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        I18n.load(this)
        Pins.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> currentJob?.cancel()
            ACTION_STOP_ALL -> {
                pending.clear()
                publishQueue()
                currentJob?.cancel()
            }
            ACTION_REMOVE -> {
                val id = intent.getLongExtra(EXTRA_JOB_ID, -1)
                pending.removeAll { it.id == id }
                publishQueue()
            }
            else -> intent?.let { jobFrom(it) }?.let { enqueue(it) }
        }
        if (!looping) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun jobFrom(i: Intent): UploadJob? {
        val uris = IntentCompat.getParcelableArrayListExtra(i, EXTRA_URIS, Uri::class.java) ?: return null
        val host = i.getStringExtra(EXTRA_HOST) ?: return null
        return UploadJob(
            id = System.currentTimeMillis(),
            label = i.getStringExtra(EXTRA_LABEL) ?: "Upload",
            host = host,
            port = i.getIntExtra(EXTRA_PORT, 2121),
            remote = i.getStringExtra(EXTRA_REMOTE) ?: "/data",
            isTree = i.getBooleanExtra(EXTRA_IS_TREE, false),
            resume = i.getBooleanExtra(EXTRA_RESUME, true),
            connections = i.getIntExtra(EXTRA_CONNECTIONS, 2),
            extractZips = i.getBooleanExtra(EXTRA_EXTRACT_ZIPS, false),
            uris = uris,
        )
    }

    private fun enqueue(job: UploadJob) {
        createChannels()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildProgressNotification(job.label, S.waitingNotif, 0, true),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        pending.addLast(job)
        publishQueue()
        if (!looping) {
            looping = true
            acquireLocks()
            scope.launch { runQueue() }
        }
    }

    /** Runs on Main; each upload itself runs on IO. */
    private suspend fun runQueue() {
        while (true) {
            val job = pending.removeFirstOrNull() ?: break
            publishQueue()
            val j = scope.launch(Dispatchers.IO) { runUpload(job) }
            currentJob = j
            j.join()
        }
        currentJob = null
        looping = false
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishQueue() {
        UploadState.queue.value = pending.toList()
    }

    private suspend fun runUpload(job: UploadJob) {
        val state = UploadState.progress
        val lock = Any()
        val startTime = SystemClock.elapsedRealtime()
        state.value = Progress(running = true, jobLabel = job.label, message = S.scanning)

        var files = 0
        var totalBytes = 0L
        var skipped = 0L
        var ok = false
        var message = ""
        try {
            val scanner = FileScanner(this)
            val entries = if (job.isTree) job.uris.flatMap { scanner.scanTree(it) } else scanner.scanFiles(job.uris)
            val fileEntries = entries.filter { !it.isDir }
            files = fileEntries.size
            totalBytes = fileEntries.sumOf { it.size }
            state.value = state.value.copy(totalFiles = files, totalBytes = totalBytes,
                message = S.connectingTo(job.host))

            // PlayBeam Server payload if it is running on the PS5 (faster), otherwise FTP
            val viaServer = ServerClient.isRunning(job.host)
            val uploader: Uploader =
                if (viaServer) ServerUploader(this, job.host, job.remote, job.connections)
                else FtpUploader(this, job.host, job.port, job.remote, job.connections)

            var done = 0L   // progress bar (sent + skipped)
            var sent = 0L   // really sent, for the speed
            var lastUi = 0L
            fun refresh(force: Boolean = false) { // call inside synchronized(lock)
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastUi < 300) return
                lastUi = now
                val secs = ((now - startTime) / 1000.0).coerceAtLeast(0.001)
                val p = state.value.copy(doneBytes = done, speedBytesPerSec = (sent / secs).toLong())
                state.value = p
                val waiting = UploadState.queue.value.size
                val queued = if (waiting > 0) S.nWaiting(waiting) else ""
                notifyProgress(job.label, "${p.currentFile}\n${formatBytes(p.speedBytesPerSec)}/s$queued",
                    (p.fraction * 100).toInt())
            }

            uploader.upload(
                entries, job.resume,
                UploadCallbacks(
                    onFileStart = { e -> synchronized(lock) { state.value = state.value.copy(currentFile = e.relativePath, message = "") } },
                    onBytes = { n -> synchronized(lock) { done += n; sent += n; refresh() } },
                    onSkipped = { n -> synchronized(lock) { done += n; skipped += n; refresh() } },
                    onFileDone = {
                        synchronized(lock) {
                            state.value = state.value.copy(doneFiles = state.value.doneFiles + 1, message = "")
                            refresh(force = true)
                        }
                    },
                    onStatus = { msg -> synchronized(lock) { state.value = state.value.copy(message = msg) } },
                ),
            )
            // ---- extract uploaded .zip files on the PS5 ----
            var zipNote = ""
            val zips = fileEntries.filter { it.relativePath.endsWith(".zip", ignoreCase = true) }
            if (job.extractZips && zips.isNotEmpty()) {
                if (!viaServer) {
                    zipNote = S.zipsNotExtracted
                } else {
                    var extracted = 0
                    for ((n, z) in zips.withIndex()) {
                        val remoteZip = "${job.remote.trimEnd('/')}/${z.relativePath}"
                        val dest = remoteZip.substringBeforeLast('/').ifEmpty { "/" }
                        val name = z.relativePath.substringAfterLast('/')
                        extracted += extractOnPs5(job.host, remoteZip, dest) { done, total ->
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            synchronized(lock) {
                                state.value = state.value.copy(currentFile = name,
                                    message = S.extractingPct(name, n + 1, zips.size, pct))
                            }
                            notifyProgress(job.label, S.extracting(name), pct)
                        }
                    }
                    zipNote = S.extractedNote(extracted, zips.size)
                }
            }

            val skippedNote = if (skipped > 0) S.skippedNote(formatBytes(skipped)) else ""
            val via = if (viaServer) S.viaServer else ""
            message = S.uploadComplete(via, files, formatBytes(totalBytes)) + skippedNote + zipNote
            ok = true
        } catch (e: CancellationException) {
            message = S.uploadStopped
        } catch (e: Exception) {
            // a socket closed by Stop shows up as an IOException
            message = if (!currentCoroutineContext().isActive) S.uploadStopped else "❌ ${friendlyError(this, job.host, e)}"
        }

        val duration = SystemClock.elapsedRealtime() - startTime
        state.value = state.value.copy(
            running = false,
            doneBytes = if (ok) totalBytes else state.value.doneBytes,
            message = message,
        )
        Store.addHistory(this, HistoryEntry(
            time = System.currentTimeMillis(), label = job.label,
            destination = "${job.host}:${job.remote}", ok = ok, message = message,
            bytes = totalBytes, files = files, durationMs = duration,
        ))
        UploadState.historyVersion.update { it + 1 }
        notifyFinished(job, ok, message, duration)
    }

    /** Extracts one ZIP on the PS5 then deletes it. Stopping the upload closes the connection. */
    private suspend fun extractOnPs5(host: String, zip: String, dest: String, onProgress: (Long, Long) -> Unit): Int =
        coroutineScope {
            val client = ServerClient(host)
            var finished = false
            val watcher = launch { try { awaitCancellation() } finally { if (!finished) client.close() } }
            try {
                client.use {
                    val count = it.unzip(zip, dest, onProgress)
                    it.rm(zip)
                    count
                }
            } finally {
                finished = true
                watcher.cancel()
            }
        }

    // ---- locks ----

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
        wifiLock = null
        wakeLock = null
    }

    // ---- notifications ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, S.chProgress, NotificationManager.IMPORTANCE_LOW)
        )
        // Default importance → sound / vibration when an upload finishes
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, S.chDone, NotificationManager.IMPORTANCE_DEFAULT).apply {
                enableVibration(true)
            }
        )
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun serviceAction(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, UploadService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildProgressNotification(title: String, text: String, percent: Int, indeterminate: Boolean): Notification {
        val b = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_playbeam)
            .setContentTitle(if (indeterminate) title else "$title — $percent%")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, S.stop, serviceAction(ACTION_STOP, 1))
        if (UploadState.queue.value.isNotEmpty()) b.addAction(0, S.stopAll, serviceAction(ACTION_STOP_ALL, 2))
        return b.build()
    }

    private fun notifyProgress(title: String, text: String, percent: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildProgressNotification(title, text, percent, false))
    }

    private fun notifyFinished(job: UploadJob, ok: Boolean, message: String, durationMs: Long) {
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_playbeam)
            .setContentTitle(if (ok) S.sentToPs5(job.label) else S.notCompleted(job.label))
            .setContentText("$message • ${formatDuration(durationMs)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message • ${formatDuration(durationMs)}"))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(1000 + (job.id % 1000).toInt(), n)
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_STOP_ALL = "stopAll"
        const val ACTION_REMOVE = "remove"
        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_LABEL = "label"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_REMOTE = "remote"
        const val EXTRA_URIS = "uris"
        const val EXTRA_IS_TREE = "isTree"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_CONNECTIONS = "connections"
        const val EXTRA_EXTRACT_ZIPS = "extractZips"
        private const val CH_PROGRESS = "upload"
        private const val CH_DONE = "done"
        private const val NOTIF_ID = 1
    }
}
