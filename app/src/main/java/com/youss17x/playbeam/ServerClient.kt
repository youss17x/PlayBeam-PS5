package com.youss17x.playbeam

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Port of the PlayBeam Server payload running on the PS5. */
const val SERVER_PORT = 9113

/**
 * One connection to the PlayBeam Server payload (see payload/main.c for the protocol).
 * Not thread-safe: use one client per thread.
 */
class ServerClient(host: String, port: Int = SERVER_PORT, connectTimeoutMs: Int = 4_000) : Closeable {
    private val socket = Socket().apply {
        sendBufferSize = 4 shl 20
        tcpNoDelay = true
        connect(InetSocketAddress(host, port), connectTimeoutMs)
        soTimeout = 60_000
    }
    private val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
    private val output = BufferedOutputStream(socket.getOutputStream(), 1 shl 20)

    private fun readLine(): String {
        val bytes = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("PlayBeam Server closed the connection")
            if (b == '\n'.code) break
            bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name()).trimEnd('\r')
    }

    private fun send(line: String) {
        require('\n' !in line) { "Invalid name" }
        output.write("$line\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun ok(reply: String): String {
        if (!reply.startsWith("OK")) throw IOException(reply.removePrefix("ERR ").ifBlank { "Server error" })
        return reply
    }

    private fun command(line: String): String { send(line); return ok(readLine()) }

    fun hello(): String = command("HELLO")

    fun mkdirs(path: String) { command("MKDIR $path") }

    /** Size of a file on the PS5, or -1 if it doesn't exist. */
    fun size(path: String): Long = command("SIZE $path").substringAfter(' ').trim().toLongOrNull() ?: -1

    fun rm(path: String) { command("RM $path") }

    fun shutdownServer() { command("SHUTDOWN") }

    /**
     * Writes [length] bytes from [source] to [path], starting at [offset] (0 = new file).
     * [onBytes] is called for every chunk sent; [checkActive] may throw to cancel.
     */
    fun put(
        path: String,
        offset: Long,
        length: Long,
        source: InputStream,
        buf: ByteArray,
        onBytes: (Long) -> Unit,
        checkActive: () -> Unit,
    ) {
        send("PUT $offset $length $path")
        val reply = readLine()
        if (reply != "READY") throw IOException(reply.removePrefix("ERR ").ifBlank { "Server refused $path" })
        var left = length
        while (left > 0) {
            checkActive()
            val n = source.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) {
                close() // the server is still waiting for bytes: this connection can't be reused
                throw IOException("File changed while uploading: $path")
            }
            output.write(buf, 0, n)
            left -= n
            onBytes(n.toLong())
        }
        output.flush()
        ok(readLine())
    }

    /** Extracts [zip] into [destDir] on the PS5. Returns the number of files extracted. */
    fun unzip(zip: String, destDir: String, onProgress: (done: Long, total: Long) -> Unit): Int {
        require('\t' !in zip && '\t' !in destDir) { "Invalid name" }
        send("UNZIP $zip\t$destDir")
        while (true) {
            val line = readLine()
            if (line.startsWith("PROG ")) {
                val parts = line.split(' ')
                onProgress(parts.getOrNull(1)?.toLongOrNull() ?: 0, parts.getOrNull(2)?.toLongOrNull() ?: 0)
                continue
            }
            return ok(line).substringAfter(' ', "0").trim().toIntOrNull() ?: 0
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** True if the PlayBeam Server payload answers on [host]. */
        suspend fun isRunning(host: String, timeoutMs: Int = 1_500): Boolean = withContext(Dispatchers.IO) {
            if (host.isBlank()) return@withContext false
            runCatching {
                ServerClient(host, SERVER_PORT, timeoutMs).use { c ->
                    c.socket.soTimeout = timeoutMs
                    c.hello().startsWith("OK PlayBeam")
                }
            }.getOrDefault(false)
        }
    }
}

/** Common shape of the FTP and PlayBeam Server uploaders. */
interface Uploader {
    suspend fun upload(entries: List<Entry>, resume: Boolean, cb: UploadCallbacks)
}

/** Uploads through the PlayBeam Server payload over [connections] parallel connections. */
class ServerUploader(
    private val context: Context,
    private val host: String,
    remoteBase: String,
    connections: Int,
) : Uploader {
    private val base = remoteBase.trimEnd('/')
    private val wanted = connections.coerceIn(1, 4)

    private fun remotePath(e: Entry) = "$base/${e.relativePath}"

    override suspend fun upload(entries: List<Entry>, resume: Boolean, cb: UploadCallbacks) = coroutineScope {
        val dirs = entries.filter { it.isDir }
        val files = entries.filter { !it.isDir }

        // Folders (the server also creates parent folders of every file; this keeps empty ones)
        Worker().use { w -> for (d in dirs) { currentCoroutineContext().ensureActive(); w.retry(cb) { it.mkdirs(remotePath(d)) } } }

        val queue = Channel<Entry>(Channel.UNLIMITED)
        files.forEach { queue.trySend(it) }
        queue.close()

        val count = wanted.coerceAtMost(maxOf(1, files.size))
        (0 until count).map {
            launch(Dispatchers.IO) {
                Worker().use { w ->
                    val buf = ByteArray(1 shl 20)
                    for (e in queue) w.uploadFile(e, resume, cb, buf)
                }
            }
        }.joinAll()
    }

    /** One connection, reopened automatically when it drops. */
    private inner class Worker : Closeable {
        private var client: ServerClient? = null

        private fun client(): ServerClient = client ?: ServerClient(host).also { client = it }

        override fun close() { client?.close(); client = null }

        suspend fun retry(cb: UploadCallbacks, block: suspend (ServerClient) -> Unit) {
            var attempt = 0
            while (true) {
                try {
                    block(client())
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    close()
                    if (++attempt > MAX_RETRIES) throw e
                    cb.onStatus("Connection lost — retrying ($attempt/$MAX_RETRIES)…")
                    delay(2_000L * attempt)
                }
            }
        }

        suspend fun uploadFile(e: Entry, resume: Boolean, cb: UploadCallbacks, buf: ByteArray) = coroutineScope {
            currentCoroutineContext().ensureActive()
            val remote = remotePath(e)
            cb.onFileStart(e)
            var reported = 0L
            var finished = false
            // closes the socket if the upload is stopped while blocked on the network
            val watcher = launch { try { awaitCancellation() } finally { if (!finished) close() } }
            try {
                retry(cb) { c ->
                    val existing = if (resume || reported > 0) c.size(remote) else -1
                    val offset = when {
                        existing == e.size && e.size > 0 -> e.size
                        existing in 1 until e.size -> existing
                        else -> 0L
                    }
                    cb.onSkipped(offset - reported)
                    reported = offset
                    if (offset == e.size && e.size > 0) return@retry

                    val input = context.contentResolver.openInputStream(e.uri!!)
                        ?: throw IOException("Cannot read ${e.relativePath}")
                    input.use { ins ->
                        var skip = offset
                        while (skip > 0) {
                            val s = ins.skip(skip)
                            if (s <= 0) { if (ins.read() < 0) throw IOException("File is shorter than expected"); skip-- }
                            else skip -= s
                        }
                        c.put(remote, offset, e.size - offset, ins, buf,
                            onBytes = { n -> reported += n; cb.onBytes(n) },
                            checkActive = { if (!watcher.isActive) throw CancellationException("Stopped") })
                    }
                }
            } finally {
                finished = true
                watcher.cancel()
            }
            cb.onFileDone(e)
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
    }
}
