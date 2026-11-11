package com.youss17x.playbeam

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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

/** Details of a file or folder. For folders [size] is the total of everything inside (-1 if unknown). */
data class RemoteDetails(val isDir: Boolean, val size: Long, val mtime: Long?, val files: Int, val dirs: Int)

fun joinPath(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
fun parentPath(path: String) = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }

/** What the PS5 file browser needs: implemented over FTP and over PlayBeam Server. */
interface RemoteFs {
    /** Shown in the browser, e.g. "PlayBeam Server" or "FTP". */
    val via: String
    suspend fun list(path: String): List<RemoteFile>
    suspend fun makeDir(path: String)
    suspend fun renamePath(from: String, to: String)
    suspend fun deletePath(path: String, isDir: Boolean)
    suspend fun download(ctx: Context, remotePath: String, isDir: Boolean, treeUri: Uri, onBytes: (String, Long) -> Unit)
    fun closeAsync()

    /** Copy / free space / folder sizes need PlayBeam Server. */
    val canCopy: Boolean
    suspend fun copy(from: String, to: String, onProgress: (Long, Long) -> Unit)
    suspend fun move(from: String, to: String, onProgress: (Long, Long) -> Unit)
    suspend fun details(path: String, isDir: Boolean): RemoteDetails
    /** Total and free bytes, or null if unknown. */
    suspend fun freeSpace(path: String): Pair<Long, Long>?
    /** Writes a small file, creating its parent folders. */
    suspend fun writeFile(path: String, bytes: ByteArray)
}

/** Creates a file in a phone folder (SAF document), guessing its type from the name. */
internal fun createPhoneFile(ctx: Context, parentDoc: Uri, name: String): Uri {
    val ext = name.substringAfterLast('.', "").lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    return DocumentsContract.createDocument(ctx.contentResolver, parentDoc, mime, name)
        ?: throw IOException("Can't create $name on the phone")
}

internal fun createPhoneDir(ctx: Context, parentDoc: Uri, name: String): Uri =
    DocumentsContract.createDocument(ctx.contentResolver, parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, name)
        ?: throw IOException("Can't create folder $name on the phone")

internal fun treeRoot(treeUri: Uri): Uri =
    DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

/** One FTP connection used by the PS5 file browser. All calls run on the IO thread, one at a time. */
class FtpSession(private val host: String, private val port: Int) : RemoteFs {
    override val via = "FTP"
    override val canCopy = false

    override suspend fun copy(from: String, to: String, onProgress: (Long, Long) -> Unit) {
        throw IOException("Copying needs PlayBeam Server (start it in Settings)")
    }

    override suspend fun move(from: String, to: String, onProgress: (Long, Long) -> Unit) = io {
        if (!this.rename(from, to)) throw IOException("Can't move: ${replyString.trim()}")
    }

    override suspend fun details(path: String, isDir: Boolean): RemoteDetails = io {
        val mtime = runCatching {
            // "YYYYMMDDhhmmss" → seconds since 1970
            getModificationTime(path)?.trim()?.takeLast(14)?.let {
                java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(it)?.time?.div(1000)
            }
        }.getOrNull()
        if (isDir) RemoteDetails(true, -1, mtime, 0, 0)
        else {
            val size = runCatching { if (sendCommand("SIZE", path) == 213) replyString.trim().substringAfter(' ').toLong() else -1L }
                .getOrDefault(-1L)
            RemoteDetails(false, size, mtime, 1, 0)
        }
    }

    override suspend fun freeSpace(path: String): Pair<Long, Long>? = null

    override suspend fun writeFile(path: String, bytes: ByteArray) = io {
        // create each parent folder; existing ones just fail quietly
        val parts = path.trim('/').split('/').dropLast(1)
        var dir = ""
        for (p in parts) { dir += "/$p"; makeDirectory(dir) }
        setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
        if (!storeFile(path, java.io.ByteArrayInputStream(bytes))) throw IOException("Can't write $path: ${replyString.trim()}")
    }
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

    override suspend fun list(path: String): List<RemoteFile> = io {
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

    override suspend fun makeDir(path: String) = io {
        if (!makeDirectory(path)) throw IOException("Can't create folder: ${replyString.trim()}")
    }

    override suspend fun renamePath(from: String, to: String) = io {
        if (!this.rename(from, to)) throw IOException("Can't rename: ${replyString.trim()}")
    }

    override suspend fun deletePath(path: String, isDir: Boolean) = io {
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

    /**
     * Copies a PS5 file or folder (recursively) into the phone folder [treeUri]
     * (picked with OpenDocumentTree). [onBytes] is called with the file name and bytes received.
     */
    override suspend fun download(
        ctx: Context,
        remotePath: String,
        isDir: Boolean,
        treeUri: Uri,
        onBytes: (String, Long) -> Unit,
    ) {
        val job = currentCoroutineContext()[Job]
        val root = treeRoot(treeUri)
        io {
            try {
                downloadInto(ctx, root, remotePath, isDir, onBytes) { job?.isActive == false }
            } catch (e: CancellationException) {
                // a transfer was cut in the middle: start from a fresh connection next time
                runCatching { disconnect() }
                throw e
            }
        }
    }

    private fun FTPClient.downloadInto(
        ctx: Context,
        parentDoc: Uri,
        remotePath: String,
        isDir: Boolean,
        onBytes: (String, Long) -> Unit,
        cancelled: () -> Boolean,
    ) {
        if (cancelled()) throw CancellationException("Download cancelled")
        val cr = ctx.contentResolver
        val name = remotePath.trimEnd('/').substringAfterLast('/').ifEmpty { "PS5" }
        if (isDir) {
            val dirDoc = createPhoneDir(ctx, parentDoc, name)
            val children = listFiles(remotePath).filterNotNull()
                .filter { val n = it.name.substringAfterLast('/'); n != "." && n != ".." }
            for (c in children) {
                downloadInto(ctx, dirDoc, joinPath(remotePath, c.name.substringAfterLast('/')),
                    c.isDirectory, onBytes, cancelled)
            }
        } else {
            val fileDoc = createPhoneFile(ctx, parentDoc, name)
            val out = cr.openOutputStream(fileDoc) ?: throw IOException("Can't write $name on the phone")
            out.use { os ->
                val ins = retrieveFileStream(remotePath)
                    ?: throw IOException("Can't read $remotePath: ${replyString.trim()}")
                ins.use {
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        if (cancelled()) throw CancellationException("Download cancelled")
                        val n = it.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        onBytes(name, n.toLong())
                    }
                }
            }
            if (!completePendingCommand()) throw IOException("Download of $name failed: ${replyString.trim()}")
        }
    }

    override fun closeAsync() {
        Thread { runCatching { if (ftp.isConnected) { ftp.logout(); ftp.disconnect() } } }.start()
    }
}
