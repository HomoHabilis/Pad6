package io.github.pyp6.app.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.pyp6.core.io.VNode
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [VNode] over the Storage Access Framework - how the app reaches the P-6's
 * USB drive and a user-chosen presets folder. Queries DocumentsContract
 * directly, one query per folder listing, rather than through DocumentFile,
 * which asks the provider again for every name and type.
 */
class SafNode private constructor(
    private val context: Context,
    val treeUri: Uri,
    val documentId: String,
    override val name: String,
    private val mimeType: String,
    private var size: Long,
) : VNode {
    val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
    override val length: Long get() = size

    private val resolver get() = context.contentResolver

    override fun children(): List<VNode> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val out = ArrayList<VNode>()
        resolver.query(childrenUri, COLUMNS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(SafNode(context, treeUri, c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3)))
            }
        } ?: throw IOException("$name cannot be read - is the drive still connected?")
        return out.sortedBy { it.name }
    }

    override fun createDirectory(name: String): VNode {
        val u = DocumentsContract.createDocument(resolver, uri, Document.MIME_TYPE_DIR, name)
            ?: throw IOException("could not create folder $name")
        return SafNode(context, treeUri, DocumentsContract.getDocumentId(u), name, Document.MIME_TYPE_DIR, 0)
    }

    override fun createFile(name: String, mimeType: String): VNode {
        // application/octet-stream keeps the name exactly as given; a typed
        // MIME lets some providers append or change the extension.
        val u = DocumentsContract.createDocument(resolver, uri, "application/octet-stream", name)
            ?: throw IOException("could not create $name")
        val id = DocumentsContract.getDocumentId(u)
        val real = queryName(u) ?: name
        return SafNode(context, treeUri, id, real, "application/octet-stream", 0)
    }

    private fun queryName(u: Uri): String? = runCatching {
        resolver.query(u, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    override fun openInput(): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("could not open $name")

    /**
     * Writes go through a file descriptor that is fsync'ed on close: the P-6
     * drive is FAT on removable media, and data still sitting in a cache when
     * the drive is ejected would be lost.
     */
    override fun openOutput(): OutputStream {
        val pfd = resolver.openFileDescriptor(uri, "wt") ?: throw IOException("could not write $name")
        val fos = FileOutputStream(pfd.fileDescriptor)
        return object : FilterOutputStream(fos) {
            private var written = 0L
            override fun write(b: ByteArray, off: Int, len: Int) {
                fos.write(b, off, len); written += len
            }
            override fun write(b: Int) {
                fos.write(b); written++
            }
            override fun close() {
                try {
                    fos.flush()
                    runCatching { fos.fd.sync() }
                } finally {
                    runCatching { fos.close() }
                    pfd.close()
                    size = written
                }
            }
        }
    }

    override fun delete(): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

    override fun toString(): String = name

    companion object {
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
        )

        /** The root of a granted tree, or null when it cannot be reached (drive unplugged). */
        fun fromTree(context: Context, treeUri: Uri): SafNode? = runCatching {
            val id = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            context.contentResolver.query(docUri, COLUMNS, null, null, null)?.use { c ->
                if (c.moveToFirst()) SafNode(context, treeUri, c.getString(0), c.getString(1) ?: "", c.getString(2) ?: Document.MIME_TYPE_DIR, 0)
                else null
            }
        }.getOrNull()

        @Suppress("unused")
        private fun closeQuietly(p: ParcelFileDescriptor?) = runCatching { p?.close() }
    }
}
