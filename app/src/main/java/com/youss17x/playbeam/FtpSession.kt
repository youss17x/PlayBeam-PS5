package com.youss17x.playbeam

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPConnectionClosedException
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.net.SocketException

data class RemoteFile(val name: String, val isDir: Boolean, val size: Long)

fun joinPath(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
fun parentPath(path: String) = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }

/** One FTP connection used by the PS5 file browser. All calls run on the IO thread, one at a time. */
class FtpSession(private val host: String, private val port: Int) {
    private val ftp = FTPClient()
    private val mutex = Mutex()

    private suspend fun <T> io(block: FTPClient.() -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!ftp.isConnected) ftp.openPs5(host, port)
            try {
                ftp.block()
            } catch (e: IOException) {
                // Only retry when the connection itself dropped (e.g. PS5 idle timeout)
                if (e !is FTPConnectionClosedException && e !is SocketException) throw e
                runCatching { ftp.disconnect() }
                ftp.openPs5(host, port)
                ftp.block()
            }
        }
    }

    suspend fun list(path: String): List<RemoteFile> = io {
        var files: List<FTPFile> = listFiles(path).filterNotNull()
        if (!FTPReply.isPositiveCompletion(replyCode)) {
            throw IOException("Can't open $path: ${replyString.trim()}")
        }
        if (files.isEmpty()) {
            // Some servers use a LIST format commons-net can't parse; try MLSD instead
            files = runCatching { mlistDir(path).filterNotNull() }.getOrDefault(emptyList())
        }
        files.map { RemoteFile(it.name.substringAfterLast('/'), it.isDirectory, it.size) }
            .filter { it.name != "." && it.name != ".." && it.name.isNotEmpty() }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    suspend fun makeDir(path: String) = io {
        if (!makeDirectory(path)) throw IOException("Can't create folder: ${replyString.trim()}")
    }

    suspend fun renamePath(from: String, to: String) = io {
        if (!this.rename(from, to)) throw IOException("Can't rename: ${replyString.trim()}")
    }

    suspend fun deletePath(path: String, isDir: Boolean) = io {
        if (isDir) deleteTree(path)
        else if (!deleteFile(path)) throw IOException("Can't delete: ${replyString.trim()}")
    }

    private fun FTPClient.deleteTree(dir: String) {
        val children = listFiles(dir).filterNotNull()
            .filter { val n = it.name.substringAfterLast('/'); n != "." && n != ".." }
        for (c in children) {
            val p = joinPath(dir, c.name.substringAfterLast('/'))
            if (c.isDirectory) deleteTree(p)
            else if (!deleteFile(p)) throw IOException("Can't delete $p: ${replyString.trim()}")
        }
        if (!removeDirectory(dir)) throw IOException("Can't delete $dir: ${replyString.trim()}")
    }

    fun closeAsync() {
        Thread { runCatching { if (ftp.isConnected) { ftp.logout(); ftp.disconnect() } } }.start()
    }
}
