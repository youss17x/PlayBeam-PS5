package com.youss17x.playbeam

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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

/** Callbacks may be called from several threads at once (one per connection). */
class UploadCallbacks(
    val onFileStart: (Entry) -> Unit,
    /** Bytes really sent over the network. */
    val onBytes: (Long) -> Unit,
    /** Bytes not sent because they were already on the PS5 (negative if a retry must resend some). */
    val onSkipped: (Long) -> Unit,
    val onFileDone: (Entry) -> Unit,
    val onStatus: (String) -> Unit,
)

/**
 * Uploads to the PS5 over [connections] parallel FTP connections (1–4).
 * Folders are created first, then files are shared between the connections.
 */
class FtpUploader(
    private val context: Context,
    private val host: String,
    private val port: Int,
    remoteBase: String,
    connections: Int,
) {
    private val base = remoteBase.trimEnd('/')
    private val wanted = connections.coerceIn(1, 4)
    private val createdDirs: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private fun remotePath(e: Entry) = "$base/${e.relativePath}"

    suspend fun upload(entries: List<Entry>, resume: Boolean, cb: UploadCallbacks) = coroutineScope {
        val dirs = entries.filter { it.isDir }
        val files = entries.filter { !it.isDir }

        val first = Worker().apply { connect() }
        val workers = mutableListOf(first)
        try {
            for (d in dirs) {
                currentCoroutineContext().ensureActive()
                first.withRetry(cb) { first.mkdirs(remotePath(d)) }
            }

            // Extra connections; if the PS5 refuses some, carry on with fewer.
            val count = wanted.coerceAtMost(maxOf(1, files.size))
            repeat(count - 1) {
                runCatching { Worker().apply { connect() } }.getOrNull()?.let { workers += it }
            }
            if (workers.size < count) cb.onStatus("Using ${workers.size} connection(s)")

            val queue = Channel<Entry>(Channel.UNLIMITED)
            files.forEach { queue.trySend(it) }
            queue.close()

            workers.map { w ->
                launch(Dispatchers.IO) {
                    val buf = ByteArray(1 shl 20)
                    for (e in queue) w.uploadFile(e, resume, cb, buf)
                }
            }.joinAll()
        } finally {
            workers.forEach { it.disconnect() }
        }
    }

    /** One FTP connection. */
    private inner class Worker {
        private val ftp = FTPClient()

        fun connect() = ftp.openPs5(host, port)

        fun disconnect() {
            runCatching { if (ftp.isConnected) { ftp.logout(); ftp.disconnect() } }
        }

        private fun reconnect() {
            runCatching { ftp.disconnect() }
            ftp.openPs5(host, port)
        }

        /** Creates each folder of the path one by one (like mkdir -p). */
        fun mkdirs(path: String) {
            var cur = ""
            for (part in path.split('/').filter { it.isNotEmpty() }) {
                cur += "/$part"
                if (cur !in createdDirs) {
                    ftp.makeDirectory(cur) // fails harmlessly if it already exists
                    createdDirs += cur
                }
            }
        }

        /** Size of a file on the PS5, or -1 if it doesn't exist / the server doesn't support SIZE. */
        private fun remoteSize(path: String): Long {
            val code = ftp.sendCommand("SIZE", path)
            if (code != 213) return -1
            return ftp.replyString.trim().substringAfter(' ').trim().toLongOrNull() ?: -1
        }

        /** Runs [block]; if the connection drops, reconnects and tries again (up to 3 times). */
        suspend fun withRetry(cb: UploadCallbacks, block: suspend () -> Unit) {
            var attempt = 0
            while (true) {
                try {
                    block()
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (++attempt > MAX_RETRIES) throw e
                    cb.onStatus("Connection lost — retrying ($attempt/$MAX_RETRIES)…")
                    delay(2_000L * attempt)
                    runCatching { reconnect() }
                }
            }
        }

        /**
         * If [resume] is true, a file already on the PS5 with the same size is skipped and a
         * partial one is continued (APPE) instead of starting over.
         */
        suspend fun uploadFile(e: Entry, resume: Boolean, cb: UploadCallbacks, buf: ByteArray) {
            currentCoroutineContext().ensureActive()
            val remote = remotePath(e)
            cb.onFileStart(e)
            var reported = 0L // bytes of this file already counted in the progress
            withRetry(cb) {
                mkdirs(remote.substringBeforeLast('/'))

                val existing = if (resume || reported > 0) remoteSize(remote) else -1
                val offset = when {
                    existing == e.size && e.size > 0 -> e.size      // already complete
                    existing in 1 until e.size -> existing          // partial → continue
                    else -> 0L
                }
                cb.onSkipped(offset - reported)
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
                            cb.onBytes(n.toLong())
                        }
                    }
                }
                if (!ftp.completePendingCommand()) {
                    throw IOException("Transfer failed for $remote: ${ftp.replyString?.trim()}")
                }
            }
            cb.onFileDone(e)
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
