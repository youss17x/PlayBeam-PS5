package com.youss17x.playbeam

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * A device found on the network.
 * [ftpPort] is null when only the PlayBeam Server answered.
 */
data class FoundDevice(val host: String, val ftpPort: Int?, val banner: String, val server: Boolean)

/**
 * Finds PS5s on the local network: the PlayBeam Server payload (port 9113)
 * and FTP servers (etaHEN / FTP payloads) on the usual ports.
 * Scans every address of the phone's /24 subnet.
 */
object PS5Discovery {

    /** Most likely PS5 FTP ports first. */
    private val FTP_PORTS = listOf(2121, 1337, 21)

    private fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    suspend fun scan(context: Context, onProgress: (Float) -> Unit): List<FoundDevice> = coroutineScope {
        val ip = localIpv4(context) ?: throw IOException("Not connected to Wi-Fi")
        val base = ip.substringBeforeLast('.')
        val own = ip.substringAfterLast('.').toInt()
        val hosts = (1..254).filter { it != own }.map { "$base.$it" }

        val limit = Semaphore(64)
        val done = AtomicInteger()
        hosts.map { h ->
            async(Dispatchers.IO) {
                limit.withPermit {
                    val result = probeHost(h)
                    onProgress(done.incrementAndGet().toFloat() / hosts.size)
                    result
                }
            }
        }.awaitAll()
            .filterNotNull()
            .sortedWith(compareBy({ !it.server }, { it.host.substringAfterLast('.').toInt() }))
    }

    private fun probeHost(host: String): FoundDevice? {
        val server = probeServer(host)
        var ftp: Pair<Int, String>? = null
        for (port in FTP_PORTS) {
            probeFtp(host, port)?.let { ftp = port to it }
            if (ftp != null) break
        }
        if (!server && ftp == null) return null
        return FoundDevice(host, ftp?.first, ftp?.second.orEmpty(), server)
    }

    private fun probeServer(host: String): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, SERVER_PORT), 300)
            s.soTimeout = 1500
            s.getOutputStream().write("HELLO\n".toByteArray())
            s.getInputStream().bufferedReader().readLine().orEmpty().startsWith("OK PlayBeam")
        }
    } catch (e: Exception) {
        false
    }

    /** Connects and reads the FTP greeting ("220 ..."). Anything else is ignored. */
    private fun probeFtp(host: String, port: Int): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 300)
            s.soTimeout = 1500
            val line = s.getInputStream().bufferedReader().readLine().orEmpty()
            if (line.startsWith("220")) line.removePrefix("220").trimStart('-', ' ').trim() else null
        }
    } catch (e: Exception) {
        null
    }
}
