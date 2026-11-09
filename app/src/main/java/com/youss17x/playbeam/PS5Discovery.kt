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

data class FoundDevice(val host: String, val port: Int, val banner: String)

/**
 * Finds FTP servers on the local network (the PS5 FTP server of etaHEN / FTP payloads).
 * Scans every address of the phone's /24 subnet on the usual PS5 FTP ports.
 */
object PS5Discovery {

    /** Most likely PS5 ports first. */
    private val PORTS = listOf(2121, 1337, 21)

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
            .sortedWith(compareBy({ PORTS.indexOf(it.port) }, { it.host.substringAfterLast('.').toInt() }))
    }

    private fun probeHost(host: String): FoundDevice? {
        for (port in PORTS) probe(host, port)?.let { return it }
        return null
    }

    /** Connects and reads the FTP greeting ("220 ..."). Anything else is ignored. */
    private fun probe(host: String, port: Int): FoundDevice? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 300)
            s.soTimeout = 1500
            val line = s.getInputStream().bufferedReader().readLine().orEmpty()
            if (line.startsWith("220")) {
                FoundDevice(host, port, line.removePrefix("220").trimStart('-', ' ').trim())
            } else null
        }
    } catch (e: Exception) {
        null
    }
}
