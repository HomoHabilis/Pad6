package io.github.pyp6.core.wavetable

import io.github.pyp6.core.io.SafeName
import io.github.pyp6.core.model.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The user's own waveforms (drawn or imported), kept for good - a drawing
 * cannot be retyped. Stored as the desktop app's waveforms.json, and
 * exchanged as its .p6wf packs (LZMA-compressed JSON, points as int16), so
 * packs move between phone and computer unchanged.
 */
class WaveLibrary(private val file: File) {
    private val json = Project.json
    private val mapSer = MapSerializer(String.serializer(), WaveEntry.serializer())

    fun load(): LinkedHashMap<String, WaveEntry> {
        val out = LinkedHashMap<String, WaveEntry>()
        runCatching {
            val m = json.decodeFromString(mapSer, file.readText())
            for ((name, e) in m) if (e.isUsable) out[name] = e.copy(name = name, group = e.group ?: Wavetable.DEFAULT_USER_GROUP)
        }
        return out
    }

    fun save(entries: Map<String, WaveEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(mapSer, entries))
        tmp.renameTo(file)
    }

    @Serializable
    private data class PackShape(
        val name: String,
        val kind: String? = null,
        val a: List<Int>? = null,
        val b: List<Int>? = null,
        val shapes: List<List<Int>>? = null,
        val labels: List<String>? = null,
    )

    @Serializable
    private data class Pack(val format: Int = FORMAT, val group: String? = null, val app: String? = null, val shapes: List<PackShape>)

    companion object {
        const val FORMAT = 2
        const val EXTENSION = ".p6wf"

        private fun q(points: List<Double>) = points.map { Math.rint(it.coerceIn(-1.0, 1.0) * 32767).toInt() }
        private fun dq(values: List<Int>) = values.map { it / 32767.0 }

        fun packFileName(group: String) = SafeName.baseName(group) + EXTENSION

        /** One folder of shapes as a shareable .p6wf pack. */
        fun exportPack(group: String, entries: List<WaveEntry>, appVersion: String? = null): ByteArray {
            val shapes = entries.mapNotNull { e ->
                when {
                    e.isMulti -> PackShape(e.name, "multi", shapes = e.shapes!!.map { q(it) }, labels = e.labels ?: emptyList())
                    !e.a.isNullOrEmpty() -> PackShape(e.name, a = q(e.a), b = q(e.b ?: e.a))
                    else -> null
                }
            }
            require(shapes.isNotEmpty()) { "this folder holds no drawn shapes" }
            val text = Project.json.encodeToString(Pack.serializer(), Pack(FORMAT, group, appVersion, shapes))
            val bos = ByteArrayOutputStream()
            XZOutputStream(bos, LZMA2Options(6)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return bos.toByteArray()
        }

        /** Reads a pack back to (group, entries). */
        fun importPack(bytes: ByteArray): Pair<String, List<WaveEntry>> {
            val text = XZInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes(), Charsets.UTF_8) }
            val pack = Project.json.decodeFromString(Pack.serializer(), text)
            require(pack.format <= FORMAT) { "this pack was written by a newer PyP6 (format ${pack.format}, this build reads $FORMAT)" }
            val group = pack.group ?: Wavetable.DEFAULT_USER_GROUP
            val out = pack.shapes.mapNotNull { sh ->
                if (sh.kind == "multi") {
                    val s = sh.shapes.orEmpty().map { dq(it) }
                    if (s.isEmpty()) null else WaveEntry("multi", sh.name, group, shapes = s.take(Wavetable.MULTI_MAX),
                        labels = sh.labels.orEmpty().take(Wavetable.MULTI_MAX))
                } else {
                    val a = dq(sh.a.orEmpty())
                    if (a.isEmpty()) null else WaveEntry("draw", sh.name, group, a, dq(sh.b.orEmpty()).ifEmpty { a })
                }
            }
            require(out.isNotEmpty()) { "the pack holds no usable shapes" }
            return group to out
        }
    }
}
