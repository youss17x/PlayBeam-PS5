package com.youss17x.playbeam

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException

class FtpUploader(
    private val context: Context,
    private val host: String,
    private val port: Int,
    remoteBase: String,
) {
    private val base = remoteBase.trimEnd('/').ifEmpty { "" }
    private val ftp = FTPClient()
    private val createdDirs = HashSet<String>()

    fun connect() {
        ftp.controlEncoding = "UTF-8"       // Arabic / accented file names
        ftp.connectTimeout = 5_000
        ftp.defaultTimeout = 15_000
        ftp.connect(host, port)
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
            ftp.disconnect()
            throw IOException("PS5 refused the connection (code ${ftp.replyCode})")
        }
        // PS5 FTP servers accept any login
        ftp.login("anonymous", "")
        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        ftp.bufferSize = 1 shl 20
        ftp.soTimeout = 30_000
    }

    fun disconnect() {
        runCatching { if (ftp.isConnected) { ftp.logout(); ftp.disconnect() } }
    }

    /** Creates each folder of the path one by one (like mkdir -p). */
    private fun mkdirs(path: String) {
        if (path.isEmpty() || path == "/") return
        var cur = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            cur += "/$part"
            if (createdDirs.add(cur)) ftp.makeDirectory(cur) // fails harmlessly if it already exists
        }
    }

    /**
     * Uploads every entry. [onBytes] is called with the number of bytes just sent,
     * [onFileStart] / [onFileDone] around each file.
     */
    suspend fun upload(
        entries: List<Entry>,
        onFileStart: (Entry) -> Unit,
        onBytes: (Int) -> Unit,
        onFileDone: (Entry) -> Unit,
    ) {
        val buf = ByteArray(1 shl 20)
        for (e in entries) {
            currentCoroutineContext().ensureActive()
            val remote = "$base/${e.relativePath}"
            if (e.isDir) { mkdirs(remote); continue }

            mkdirs(remote.substringBeforeLast('/'))
            onFileStart(e)
            val input = context.contentResolver.openInputStream(e.uri!!)
                ?: throw IOException("Cannot read ${e.relativePath}")
            input.use { ins ->
                val out = ftp.storeFileStream(remote)
                    ?: throw IOException("PS5 refused $remote: ${ftp.replyString?.trim()}")
                out.use { os ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        onBytes(n)
                    }
                }
            }
            if (!ftp.completePendingCommand()) {
                throw IOException("Transfer failed for $remote: ${ftp.replyString?.trim()}")
            }
            onFileDone(e)
        }
    }
}
