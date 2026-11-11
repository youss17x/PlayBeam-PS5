package com.youss17x.playbeam

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** The PS5 file browser over the PlayBeam Server payload. One call at a time, on the IO thread. */
class ServerSession(private val host: String) : RemoteFs {
    override val via = "PlayBeam Server"
    override val canCopy = true

    override suspend fun copy(from: String, to: String, onProgress: (Long, Long) -> Unit) =
        io { it.copy(from, to, move = false, onProgress = onProgress) }

    override suspend fun move(from: String, to: String, onProgress: (Long, Long) -> Unit) =
        io { it.copy(from, to, move = true, onProgress = onProgress) }

    override suspend fun details(path: String, isDir: Boolean) = io { it.stat(path) }

    override suspend fun freeSpace(path: String): Pair<Long, Long>? = runCatching { io { it.df(path) } }.getOrNull()

    private val mutex = Mutex()
    private var client: ServerClient? = null

    private suspend fun <T> io(block: (ServerClient) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val reused = client != null
            try {
                block(client ?: ServerClient(host).also { client = it })
            } catch (e: ServerError) {
                throw e // the server answered with an error: connection is fine
            } catch (e: IOException) {
                if (!reused) { client?.close(); client = null; throw e } // couldn't connect at all
                // connection dropped: reconnect once
                client?.close()
                client = ServerClient(host).also { client = it }
                block(client!!)
            }
        }
    }

    override suspend fun list(path: String) = io { it.list(path) }

    override suspend fun writeFile(path: String, bytes: ByteArray) = io {
        it.mkdirs(path.substringBeforeLast('/', "/").ifBlank { "/" })
        it.put(path, 0, bytes.size.toLong(), java.io.ByteArrayInputStream(bytes), ByteArray(64 * 1024),
            onBytes = {}, checkActive = {})
    }

    override suspend fun makeDir(path: String) = io { it.mkdirs(path) }

    override suspend fun renamePath(from: String, to: String) = io { it.rename(from, to) }

    override suspend fun deletePath(path: String, isDir: Boolean) =
        io { if (isDir) it.rmtree(path) else it.rm(path) }

    override suspend fun download(
        ctx: Context,
        remotePath: String,
        isDir: Boolean,
        treeUri: Uri,
        onBytes: (String, Long) -> Unit,
    ) {
        val job = currentCoroutineContext()[Job]
        val cancelled = { job?.isActive == false }
        io { c ->
            try {
                downloadInto(c, ctx, treeRoot(treeUri), remotePath, isDir, onBytes, cancelled)
            } catch (e: CancellationException) {
                // stopped in the middle of a file: the connection can't be reused
                c.close()
                client = null
                throw e
            }
        }
    }

    private fun downloadInto(
        c: ServerClient,
        ctx: Context,
        parentDoc: Uri,
        remotePath: String,
        isDir: Boolean,
        onBytes: (String, Long) -> Unit,
        cancelled: () -> Boolean,
    ) {
        if (cancelled()) throw CancellationException("Download cancelled")
        val name = remotePath.trimEnd('/').substringAfterLast('/').ifEmpty { "PS5" }
        if (isDir) {
            val dirDoc = createPhoneDir(ctx, parentDoc, name)
            for (child in c.list(remotePath)) {
                downloadInto(c, ctx, dirDoc, joinPath(remotePath, child.name), child.isDir, onBytes, cancelled)
            }
        } else {
            val fileDoc = createPhoneFile(ctx, parentDoc, name)
            val out = ctx.contentResolver.openOutputStream(fileDoc) ?: throw IOException("Can't write $name on the phone")
            out.use { os ->
                c.get(remotePath, os, onBytes = { n -> onBytes(name, n) },
                    checkActive = { if (cancelled()) throw CancellationException("Download cancelled") })
            }
        }
    }

    override fun closeAsync() {
        val c = client
        client = null
        Thread { c?.close() }.start()
    }
}
