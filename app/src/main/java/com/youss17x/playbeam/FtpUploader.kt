package com.youss17x.playbeam

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.io.InputStream

/** Opens an FTP connection to the PS5 with the settings PS5 FTP servers expect. */
fun FTPClient.openPs5(host: String, port: Int) {
    controlEncoding = "UTF-8"       // Arabic / accented file names
    connectTimeout = 5_000
    defaultTimeout = 15_000
    connect(host, port)
    if (!FTPReply.isPositiveCompletion(replyCode)) {
        disconnect()
        throw IOException("PS5 refused the connection (code $replyCode)")
    }
    login("anonymous", "")          // PS5 FTP servers accept any login
    enterLocalPassiveMode()
    setFileType(FTP.BINARY_FILE_TYPE)
    bufferSize = 1 shl 20
    soTimeout = 30_000
}

class FtpUploader(
    private val context: Context,
    private val host: String,
    private val port: Int,
    remoteBase: String,
) {
    private val base = remoteBase.trimEnd('/')
    private val ftp = FTPClient()
    private val createdDirs = HashSet<String>()

    fun connect() = ftp.openPs5(host, port)

    fun disconnect() {
        runCatching { if (ftp.isConnected) { ftp.logout(); ftp.disconnect() } }
    }

    private fun reconnect() {
        runCatching { ftp.disconnect() }
        ftp.openPs5(host, port)
    }

    /** Creates each folder of the path one by one (like mkdir -p). */
    private fun mkdirs(path: String) {
        var cur = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            cur += "/$part"
            if (createdDirs.add(cur)) ftp.makeDirectory(cur) // fails harmlessly if it already exists
        }
    }

    /** Size of a file on the PS5, or -1 if it doesn't exist / the server doesn't support SIZE. */
    private fun remoteSize(path: String): Long {
        val code = ftp.sendCommand("SIZE", path)
        if (code != 213) return -1
        return ftp.replyString.trim().substringAfter(' ').trim().toLongOrNull() ?: -1
    }

    /** Runs [block]; if the connection drops, reconnects and tries again (up to 3 times). */
    private suspend fun withRetry(onStatus: (String) -> Unit, block: suspend () -> Unit) {
        var attempt = 0
        while (true) {
            try {
                block()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (++attempt > MAX_RETRIES) throw e
                onStatus("Connection lost — retrying ($attempt/$MAX_RETRIES)…")
                delay(2_000L * attempt)
                runCatching { reconnect() }
            }
        }
    }

    /**
     * Uploads every entry.
     * [onBytes]: bytes really sent over the network.
     * [onSkipped]: bytes not sent because they were already on the PS5 (can be negative
     *              when a retry has to resend part of a file).
     * If [resume] is true, files that already exist with the same size are skipped and
     * partial files are continued (APPE) instead of starting over.
     */
    suspend fun upload(
        entries: List<Entry>,
        resume: Boolean,
        onFileStart: (Entry) -> Unit,
        onBytes: (Long) -> Unit,
        onSkipped: (Long) -> Unit,
        onFileDone: (Entry) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        val buf = ByteArray(1 shl 20)
        for (e in entries) {
            currentCoroutineContext().ensureActive()
            val remote = "$base/${e.relativePath}"
            if (e.isDir) {
                withRetry(onStatus) { mkdirs(remote) }
                continue
            }

            onFileStart(e)
            var reported = 0L // bytes of this file already counted in the progress
            withRetry(onStatus) {
                mkdirs(remote.substringBeforeLast('/'))

                val existing = if (resume || reported > 0) remoteSize(remote) else -1
                val offset = when {
                    existing == e.size && e.size > 0 -> e.size      // already complete
                    existing in 1 until e.size -> existing          // partial → continue
                    else -> 0L
                }
                onSkipped(offset - reported)
                reported = offset
                if (offset == e.size && e.size > 0) return@withRetry

                val input = context.contentResolver.openInputStream(e.uri!!)
                    ?: throw IOException("Cannot read ${e.relativePath}")
                input.use { ins ->
                    ins.skipFully(offset)
                    val out = (if (offset > 0) ftp.appendFileStream(remote) else ftp.storeFileStream(remote))
                        ?: throw IOException("PS5 refused $remote: ${ftp.replyString?.trim()}")
                    out.use { os ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            reported += n
                            onBytes(n.toLong())
                        }
                    }
                }
                if (!ftp.completePendingCommand()) {
                    throw IOException("Transfer failed for $remote: ${ftp.replyString?.trim()}")
                }
            }
            onFileDone(e)
        }
    }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val s = skip(left)
            if (s > 0) left -= s
            else if (read() < 0) throw IOException("File is shorter than expected")
            else left--
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
    }
}
