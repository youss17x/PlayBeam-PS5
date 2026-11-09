package com.youss17x.playbeam

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns

/** One thing to create on the PS5: a folder, or a file with its source Uri. */
data class Entry(
    val uri: Uri?,
    val relativePath: String, // e.g. "MyGame/sce_sys/param.json"
    val size: Long,
    val isDir: Boolean,
)

/**
 * Lists files using DocumentsContract queries directly.
 * (DocumentFile is much slower when a folder has thousands of files.)
 */
class FileScanner(private val context: Context) {

    private val cr get() = context.contentResolver

    /** Whole folder picked with OpenDocumentTree. The folder itself is kept as the top level. */
    fun scanTree(treeUri: Uri): List<Entry> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val rootName = queryName(rootDoc) ?: "upload"
        val out = mutableListOf(Entry(null, rootName, 0, true))
        walk(treeUri, rootId, rootName, out)
        return out
    }

    private fun walk(treeUri: Uri, parentId: String, parentPath: String, out: MutableList<Entry>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )
        val subDirs = mutableListOf<Pair<String, String>>()
        cr.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val path = "$parentPath/$name"
                if (mime == Document.MIME_TYPE_DIR) {
                    out += Entry(null, path, 0, true)
                    subDirs += id to path
                } else {
                    out += Entry(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), path, size, false)
                }
            }
        }
        for ((id, path) in subDirs) walk(treeUri, id, path, out)
    }

    /** Single files picked with OpenMultipleDocuments. */
    fun scanFiles(uris: List<Uri>): List<Entry> = uris.map { uri ->
        var name = "file"
        var size = 0L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        Entry(uri, name, size, false)
    }

    private fun queryName(docUri: Uri): String? =
        cr.query(docUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
