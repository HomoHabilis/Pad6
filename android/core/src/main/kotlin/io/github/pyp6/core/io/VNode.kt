package io.github.pyp6.core.io

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A file or folder somewhere: on the P-6's USB drive, in a preset folder the
 * user picked, or in the app's own storage. On Android the first two are
 * Storage Access Framework documents; the core only sees this interface, so
 * the transfer logic is the same code everywhere and testable on a plain
 * folder.
 */
interface VNode {
    val name: String
    val isDirectory: Boolean
    val length: Long

    fun children(): List<VNode>

    /** Exact-name child, or null. */
    fun child(name: String): VNode? = children().firstOrNull { it.name == name }

    /** Child whose name matches ignoring case (FAT volumes can present "import"). */
    fun childIgnoreCase(name: String): VNode? =
        child(name) ?: children().firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun createDirectory(name: String): VNode
    fun createFile(name: String, mimeType: String = "application/octet-stream"): VNode

    fun openInput(): InputStream
    fun openOutput(): OutputStream
    fun delete(): Boolean

    fun readBytes(): ByteArray = openInput().use { it.readBytes() }
    fun readText(): String = String(readBytes(), Charsets.ISO_8859_1)

    /** Existing folder [name] (any case), or a new one. */
    fun dir(name: String): VNode {
        val c = childIgnoreCase(name)
        if (c != null && c.isDirectory) return c
        return createDirectory(name)
    }

    /** Replaces (or creates) file [name] with [bytes]. */
    fun writeFile(name: String, bytes: ByteArray, mimeType: String = "application/octet-stream"): VNode {
        child(name)?.let { if (!it.isDirectory) it.delete() }
        val f = createFile(name, mimeType)
        f.openOutput().use { it.write(bytes) }
        return f
    }

    fun writeFile(name: String, source: File, mimeType: String = "application/octet-stream"): VNode {
        child(name)?.let { if (!it.isDirectory) it.delete() }
        val f = createFile(name, mimeType)
        f.openOutput().use { out -> source.inputStream().use { it.copyTo(out) } }
        return f
    }

    fun deleteRecursively(): Boolean {
        if (isDirectory) children().forEach { it.deleteRecursively() }
        return delete()
    }
}

/** [VNode] over java.io.File. */
class FileNode(val file: File) : VNode {
    override val name: String get() = file.name
    override val isDirectory: Boolean get() = file.isDirectory
    override val length: Long get() = file.length()

    override fun children(): List<VNode> =
        (file.listFiles() ?: emptyArray()).sortedBy { it.name }.map { FileNode(it) }

    override fun child(name: String): VNode? = File(file, name).takeIf { it.exists() }?.let { FileNode(it) }

    override fun createDirectory(name: String): VNode {
        val d = File(file, name)
        if (!d.isDirectory && !d.mkdirs()) throw IOException("could not create folder $d")
        return FileNode(d)
    }

    override fun createFile(name: String, mimeType: String): VNode {
        val f = File(file, name)
        f.parentFile?.mkdirs()
        if (!f.exists()) f.createNewFile()
        return FileNode(f)
    }

    override fun openInput(): InputStream = file.inputStream()
    override fun openOutput(): OutputStream = file.outputStream()
    override fun delete(): Boolean = file.delete()
    override fun toString() = file.path
}
