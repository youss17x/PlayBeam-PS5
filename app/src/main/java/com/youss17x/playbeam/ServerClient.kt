package com.youss17x.playbeam

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
        if (!reply.startsWith("OK")) throw ServerError(reply.removePrefix("ERR ").ifBlank { "Server error" })
        return reply
    }

    private fun command(line: String): String { send(line); return ok(readLine()) }

    fun hello(): String = command("HELLO")

    fun mkdirs(path: String) { command("MKDIR $path") }

    /** Size of a file on the PS5, or -1 if it doesn't exist. */
    fun size(path: String): Long = command("SIZE $path").substringAfter(' ').trim().toLongOrNull() ?: -1

    fun rm(path: String) { command("RM $path") }

    /**
     * Creates an empty [size]-byte file for a split upload. Returns whether pieces can be written
     * in parallel plus the drive's file system, or null if the server is too old (< 1.4).
     */
    fun create(path: String, size: Long): CreateResult? {
        send("CREATE $size $path")
        val reply = readLine()
        if (reply == "ERR unknown command") return null
        val parts = ok(reply).split(' ')
        val sparse = parts.getOrNull(1) == "SPARSE"
        return CreateResult(sparse, parts.getOrNull(if (sparse) 2 else 1) ?: "")
    }

    class CreateResult(val parallel: Boolean, val fileSystem: String)

    fun shutdownServer() { command("SHUTDOWN") }

    fun rmtree(path: String) { command("RMTREE $path") }

    /** PS5 info as key=value pairs: model, fw, uptime, cpu_temp, soc_temp, cpu_mhz, version. */
    fun info(): Map<String, String> {
        send("INFO")
        val out = mutableMapOf<String, String>()
        while (true) {
            val line = readLine()
            if (line.startsWith("OK") || line.startsWith("ERR")) { ok(line); return out }
            val eq = line.indexOf('=')
            if (eq > 0) out[line.substring(0, eq)] = line.substring(eq + 1)
        }
    }

    /** Plugged drives: "/data" plus /mnt/usbN and /mnt/extN that are really mounted, with (total, free). */
    fun drives(): List<Pair<String, Pair<Long, Long>>> {
        val out = mutableListOf<Pair<String, Pair<Long, Long>>>()
        runCatching { out += "/data" to df("/data") }
        val mntTotal = runCatching { df("/mnt").first }.getOrNull()
        val names = runCatching { list("/mnt") }.getOrDefault(emptyList())
            .filter { it.isDir && Regex("(usb|ext)\\d+").matches(it.name) }
            .sortedBy { it.name }
        for (n in names) {
            val root = "/mnt/${n.name}"
            val d = runCatching { df(root) }.getOrNull() ?: continue
            if (d.first > 0 && d.first != mntTotal) out += root to d
        }
        return out
    }

    fun stat(path: String): RemoteDetails {
        val p = command("STAT $path").split(' ')
        val size = p.getOrNull(2)?.toLongOrNull() ?: -1
        val mtime = p.getOrNull(3)?.toLongOrNull()
        return if (p.getOrNull(1) == "D") {
            RemoteDetails(true, size, mtime, p.getOrNull(4)?.toIntOrNull() ?: 0, p.getOrNull(5)?.toIntOrNull() ?: 0)
        } else {
            RemoteDetails(false, size, mtime, 1, 0)
        }
    }

    /** Total and free bytes of the drive holding [path]. */
    fun df(path: String): Pair<Long, Long> {
        val p = command("DF $path").split(' ')
        return (p.getOrNull(1)?.toLongOrNull() ?: 0) to (p.getOrNull(2)?.toLongOrNull() ?: 0)
    }

    /** Copies (or moves) a file or folder on the PS5 itself. [to] must not exist yet. */
    fun copy(from: String, to: String, move: Boolean, onProgress: (Long, Long) -> Unit) {
        require('\t' !in from && '\t' !in to) { "Invalid name" }
        send("${if (move) "MOVE" else "COPY"} $from\t$to")
        while (true) {
            val line = readLine()
            if (line.startsWith("PROG ")) {
                val parts = line.split(' ')
                onProgress(parts.getOrNull(1)?.toLongOrNull() ?: 0, parts.getOrNull(2)?.toLongOrNull() ?: 0)
                continue
            }
            ok(line)
            return
        }
    }

    fun rename(from: String, to: String) {
        require('\t' !in from && '\t' !in to) { "Invalid name" }
        command("RENAME $from\t$to")
    }

    fun list(path: String): List<RemoteFile> {
        send("LIST $path")
        val out = mutableListOf<RemoteFile>()
        while (true) {
            val line = readLine()
            if (line.startsWith("D ") || line.startsWith("F ")) {
                val rest = line.substring(2)
                val size = rest.substringBefore(' ').toLongOrNull() ?: 0
                val name = rest.substringAfter(' ', "")
                if (name.isNotEmpty()) out += RemoteFile(name, line[0] == 'D', size)
                continue
            }
            ok(line)
            return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        }
    }

    /** Copies a PS5 file into [dest]. */
    fun get(path: String, dest: java.io.OutputStream, onBytes: (Long) -> Unit, checkActive: () -> Unit) {
        val size = command("GET $path").substringAfter(' ').trim().toLongOrNull()
            ?: throw IOException("Bad reply from PlayBeam Server")
        val buf = ByteArray(1 shl 20)
        var left = size
        while (left > 0) {
            checkActive()
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("Connection lost while downloading")
            dest.write(buf, 0, n)
            left -= n
            onBytes(n.toLong())
        }
    }

    /**
     * Writes [length] bytes from [source] to [path], starting at [offset] (0 = new file).
     * [onBytes] is called for every chunk sent; [checkActive] may throw to cancel.
     * With [truncate] false the file is never cut short (one piece of a split upload).
     */
    fun put(
        path: String,
        offset: Long,
        length: Long,
        source: InputStream,
        buf: ByteArray,
        onBytes: (Long) -> Unit,
        checkActive: () -> Unit,
        truncate: Boolean = true,
    ) {
        send("${if (truncate) "PUT" else "PUTR"} $offset $length $path")
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

/** An error reported by the server itself (the connection is still fine). */
class ServerError(message: String) : IOException(message)

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

        // Big files go one at a time, each split into pieces sent over all connections at once
        val (big, small) = files.partition { wanted > 1 && it.size >= SPLIT_MIN_SIZE }
        for (e in big) uploadSplit(e, resume, cb)

        val queue = Channel<Entry>(Channel.UNLIMITED)
        small.forEach { queue.trySend(it) }
        queue.close()

        val count = wanted.coerceAtMost(maxOf(1, small.size))
        (0 until count).map {
            launch(Dispatchers.IO) {
                Worker().use { w ->
                    val buf = ByteArray(1 shl 20)
                    for (e in queue) w.uploadFile(e, resume, cb, buf)
                }
            }
        }.joinAll()
    }

    /**
     * Sends one big file as [wanted] pieces in parallel into "<name>.pbpart", then renames it.
     * Falls back to a normal upload when the drive can't take pieces out of order (exFAT USB),
     * when the server is older than 1.4, or when resuming a file that was partly sent in order.
     */
    private suspend fun uploadSplit(e: Entry, resume: Boolean, cb: UploadCallbacks) = coroutineScope {
        currentCoroutineContext().ensureActive()
        val remote = remotePath(e)
        val temp = "$remote.pbpart"
        var split = false
        var note: String? = null
        Worker().use { ctl ->
            ctl.retry(cb) { c ->
                split = false
                note = null
                val existing = if (resume) c.size(remote) else -1
                if (existing > 0) return@retry // already there, or continue it in one piece
                val r = c.create(temp, e.size)
                when {
                    r == null -> note = S.splitServerOld
                    r.parallel -> split = true
                    else -> { runCatching { c.rm(temp) }; note = S.splitNotOnDrive(r.fileSystem) }
                }
            }
            if (!split) {
                ctl.uploadFile(e, resume, cb, ByteArray(1 shl 20), note)
                return@coroutineScope
            }
        }

        cb.onFileStart(e)
        cb.onStatus(S.splitPieces(wanted))
        try {
            val piece = ((e.size / wanted) + PIECE_ALIGN - 1) / PIECE_ALIGN * PIECE_ALIGN
            (0 until wanted).map { i ->
                val start = piece * i
                val len = minOf(piece, e.size - start)
                launch(Dispatchers.IO) {
                    if (len > 0) Worker().use { w -> w.uploadRange(e, temp, start, len, cb, ByteArray(1 shl 20)) }
                }
            }.joinAll()
            Worker().use { w -> w.retry(cb) { it.rename(temp, remote) } }
        } catch (t: Throwable) {
            // don't leave a half-written temp file behind
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { ServerClient(host).use { it.rm(temp) } }
            }
            throw t
        }
        cb.onFileDone(e)
    }

    /** Opens [e] and skips to [offset]. */
    private fun openAt(e: Entry, offset: Long): InputStream {
        val ins = context.contentResolver.openInputStream(e.uri!!)
            ?: throw IOException("Cannot read ${e.relativePath}")
        try {
            var skip = offset
            while (skip > 0) {
                val s = ins.skip(skip)
                if (s <= 0) { if (ins.read() < 0) throw IOException("File is shorter than expected"); skip-- }
                else skip -= s
            }
        } catch (t: Throwable) {
            ins.close(); throw t
        }
        return ins
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

        suspend fun uploadFile(e: Entry, resume: Boolean, cb: UploadCallbacks, buf: ByteArray, note: String? = null) = coroutineScope {
            currentCoroutineContext().ensureActive()
            val remote = remotePath(e)
            cb.onFileStart(e)
            if (note != null) cb.onStatus(note)
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

                    openAt(e, offset).use { ins ->
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

        /** Sends bytes [start] until [start]+[len] of [e] into [path]; a failed piece is resent whole. */
        suspend fun uploadRange(e: Entry, path: String, start: Long, len: Long, cb: UploadCallbacks, buf: ByteArray) =
            coroutineScope {
                var reported = 0L
                var finished = false
                val watcher = launch { try { awaitCancellation() } finally { if (!finished) close() } }
                try {
                    retry(cb) { c ->
                        cb.onSkipped(-reported)
                        reported = 0
                        openAt(e, start).use { ins ->
                            c.put(path, start, len, ins, buf,
                                onBytes = { n -> reported += n; cb.onBytes(n) },
                                checkActive = { if (!watcher.isActive) throw CancellationException("Stopped") },
                                truncate = false)
                        }
                    }
                } finally {
                    finished = true
                    watcher.cancel()
                }
            }
    }

    companion object {
        private const val MAX_RETRIES = 3
        /** Files at least this big are split between the connections. */
        private const val SPLIT_MIN_SIZE = 64L shl 20
        private const val PIECE_ALIGN = 1L shl 20
    }
}
