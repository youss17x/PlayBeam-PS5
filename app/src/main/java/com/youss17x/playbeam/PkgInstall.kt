package com.youss17x.playbeam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Installs a PKG from the phone on the PS5 through etaHEN's Direct PKG Installer (DPI v1, TCP 9090):
 * the phone serves the file over HTTP on the local network (with Range support), and DPI is told
 * the URL, so the PS5 downloads and installs it without copying it to the PS5 first.
 */
object PkgState {
    enum class Phase { IDLE, STARTING, SERVING, DONE, ERROR }

    data class Info(
        val phase: Phase = Phase.IDLE,
        val name: String = "",
        val size: Long = 0,
        val served: Long = 0,
        val message: String? = null,
    ) {
        val progress: Float get() = if (size > 0) (served.toFloat() / size).coerceIn(0f, 1f) else 0f
    }

    internal val flow = MutableStateFlow(Info())
    val state: StateFlow<Info> = flow

    fun reset() { if (flow.value.phase != Phase.SERVING && flow.value.phase != Phase.STARTING) flow.value = Info() }
}

class PkgInstallService : Service() {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastActivity = System.currentTimeMillis()
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PkgState.flow.value = PkgState.flow.value.copy(phase = PkgState.Phase.IDLE, message = null)
            stopSelf()
            return START_NOT_STICKY
        }
        val uri = intent?.data ?: return START_NOT_STICKY
        val name = intent.getStringExtra(EXTRA_NAME) ?: "game.pkg"
        val size = intent.getLongExtra(EXTRA_SIZE, -1)
        val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
        val dpiPort = intent.getIntExtra(EXTRA_PORT, DPI_PORT)

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(name, S.pkgStarting, 0, true),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        acquireLocks()
        stopServer()
        stopped = false
        PkgState.flow.value = PkgState.Info(PkgState.Phase.STARTING, name, size)
        pool.execute { run(uri, name, size, host, dpiPort) }
        return START_NOT_STICKY
    }

    private fun run(uri: Uri, name: String, size: Long, host: String, dpiPort: Int) {
        try {
            val ip = phoneIp(this) ?: throw IOException(S.pkgNoWifi)
            val token = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "package.pkg" }
            val path = "/$token/$safeName"
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(0))
            server = ss
            val url = "http://$ip:${ss.localPort}$path"
            pool.execute { serve(ss, uri, path, size) }

            // ask DPI to install from our URL
            val reply = Socket().use { s ->
                try { s.connect(InetSocketAddress(host, dpiPort), 5_000) }
                catch (e: IOException) { throw IOException(S.pkgDpiUnreachable(dpiPort)) }
                s.soTimeout = 60_000
                s.getOutputStream().apply { write("{ \"url\" : \"$url\" }".toByteArray()); flush() }
                runCatching { s.getInputStream().readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
            }
            val res = Regex("\"res\"\\s*:\\s*\"?(-?\\w+)\"?").find(reply)?.groupValues?.get(1)
            if (res != null && res != "0") throw IOException(S.pkgDpiError(res))

            PkgState.flow.value = PkgState.flow.value.copy(phase = PkgState.Phase.SERVING, message = S.pkgServing)
            lastActivity = System.currentTimeMillis()
            // keep serving while the PS5 downloads; stop after a quiet period once everything was sent
            while (!stopped) {
                Thread.sleep(1_000)
                val st = PkgState.flow.value
                val idle = System.currentTimeMillis() - lastActivity
                val complete = st.size > 0 && st.served >= st.size
                val pct = (st.progress * 100).toInt()
                updateNotification(name, S.pkgServingShort(pct), pct)
                if ((complete && idle > 20_000) || idle > 10 * 60_000) break
            }
            if (!stopped) {
                val st = PkgState.flow.value
                PkgState.flow.value = st.copy(
                    phase = if (st.size > 0 && st.served >= st.size) PkgState.Phase.DONE else PkgState.Phase.ERROR,
                    message = if (st.size > 0 && st.served >= st.size) S.pkgDone else S.pkgStalled,
                )
            }
        } catch (e: Exception) {
            if (!stopped) PkgState.flow.value = PkgState.flow.value.copy(
                phase = PkgState.Phase.ERROR, message = e.message ?: S.sendFailed)
        }
        stopSelf()
    }

    /** Minimal HTTP/1.1 file server for one file, with HEAD and Range support. */
    private fun serve(ss: ServerSocket, uri: Uri, path: String, size: Long) {
        val highest = AtomicLong(0)
        while (!ss.isClosed) {
            val c = try { ss.accept() } catch (e: SocketException) { break }
            pool.execute {
                c.use { sock ->
                    try {
                        sock.soTimeout = 60_000
                        val input = sock.getInputStream().buffered()
                        val lines = mutableListOf<String>()
                        while (true) {
                            val line = readLine(input) ?: return@use
                            if (line.isEmpty()) break
                            lines += line
                        }
                        val parts = lines.firstOrNull()?.split(' ') ?: return@use
                        val method = parts.getOrNull(0) ?: return@use
                        // accept origin-form or absolute-form targets, ignore any query, and
                        // match on the random token only (the PS5 may re-encode the file name)
                        var target = parts.getOrNull(1) ?: ""
                        if (target.startsWith("http://", true) || target.startsWith("https://", true))
                            target = "/" + target.substringAfter("://").substringAfter('/', "")
                        target = target.substringBefore('?').substringBefore('#')
                        val token = path.split('/').getOrNull(1) ?: ""
                        val out = sock.getOutputStream()
                        if (token.isEmpty() || !target.startsWith("/$token")) {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            return@use
                        }
                        lastActivity = System.currentTimeMillis()
                        val range = lines.firstOrNull { it.startsWith("Range:", true) }
                            ?.substringAfter(':')?.trim()?.removePrefix("bytes=")
                        var start = 0L
                        var end = size - 1
                        var partial = false
                        if (range != null && size > 0) {
                            val a = range.substringBefore('-').trim()
                            val b = range.substringAfter('-').substringBefore(',').trim()
                            if (a.isNotEmpty()) {
                                start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
                            } else if (b.isNotEmpty()) {
                                start = maxOf(0, size - b.toLong())
                            }
                            partial = true
                        }
                        if (start > end && size > 0) {
                            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            return@use
                        }
                        val len = end - start + 1
                        val head = buildString {
                            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                            append("Content-Type: application/octet-stream\r\n")
                            append("Accept-Ranges: bytes\r\n")
                            append("Content-Length: $len\r\n")
                            if (partial) append("Content-Range: bytes $start-$end/$size\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(head.toByteArray())
                        if (method == "HEAD") return@use
                        contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                                ch.position(start)
                                val buf = java.nio.ByteBuffer.allocate(1 shl 20)
                                var left = len
                                var pos = start
                                while (left > 0 && !stopped) {
                                    buf.clear()
                                    if (left < buf.capacity()) buf.limit(left.toInt())
                                    val n = ch.read(buf)
                                    if (n <= 0) break
                                    out.write(buf.array(), 0, n)
                                    left -= n
                                    pos += n
                                    lastActivity = System.currentTimeMillis()
                                    // progress: furthest byte the PS5 has fetched
                                    val h = highest.updateAndGet { maxOf(it, pos) }
                                    val st = PkgState.flow.value
                                    if (h > st.served) PkgState.flow.value = st.copy(served = h)
                                }
                                out.flush()
                            }
                        }
                    } catch (_: IOException) {
                        // the PS5 closed the connection (it often reads in pieces)
                    }
                }
            }
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    private fun stopServer() {
        runCatching { server?.close() }
        server = null
    }

    override fun onDestroy() {
        stopped = true
        stopServer()
        pool.shutdownNow()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "playbeam:pkg").apply { acquire() }
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "playbeam:pkg").apply { acquire(6 * 60 * 60_000L) }
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, S.pkgChannel, NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(title: String, text: String, percent: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_playbeam)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, S.stop, PendingIntent.getService(this, 7,
                Intent(this, PkgInstallService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun updateNotification(title: String, text: String, percent: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(title, text, percent, false))
    }

    companion object {
        const val DPI_PORT = 9090
        private const val CHANNEL = "pkg"
        private const val NOTIF_ID = 4200
        private const val ACTION_STOP = "stop"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"

        fun start(ctx: Context, uri: Uri, name: String, size: Long, host: String, port: Int = DPI_PORT) {
            ctx.startForegroundService(Intent(ctx, PkgInstallService::class.java).setData(uri)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_SIZE, size)
                .putExtra(EXTRA_HOST, host).putExtra(EXTRA_PORT, port))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, PkgInstallService::class.java).setAction(ACTION_STOP))
        }

        /** The phone's IPv4 address on the current Wi-Fi / Ethernet network. */
        fun phoneIp(ctx: Context): String? {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
            return lp.linkAddresses.map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }
    }
}
