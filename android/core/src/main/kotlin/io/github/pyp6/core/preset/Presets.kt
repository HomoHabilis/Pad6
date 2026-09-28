package io.github.pyp6.core.preset

import io.github.pyp6.core.P6
import io.github.pyp6.core.audio.Wav
import io.github.pyp6.core.io.VNode
import io.github.pyp6.core.model.BankState
import io.github.pyp6.core.model.PadAudio
import io.github.pyp6.core.model.PadState
import io.github.pyp6.core.model.Project
import io.github.pyp6.core.model.SampleStore
import io.github.pyp6.core.model.WavetableState
import io.github.pyp6.core.pattern.P6Pattern
import io.github.pyp6.core.pattern.PatternSlot
import io.github.pyp6.core.prm.Prm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Presets: a folder with preset.json and BANK_x/PAD_n/<sample> (+ .PRM), plus
 * PATTERNS/P6_PTN*.PRM. Same layout and manifest as the desktop app, so a
 * preset saved on the phone opens on the computer and the other way round.
 */
object Presets {
    const val MANIFEST = "preset.json"
    const val FORMAT_VERSION = 4
    const val BANK_FORMAT_VERSION = 3
    const val PATTERN_DIR = "PATTERNS"

    private val json = Project.json

    data class Manifest(val raw: JsonObject) {
        val formatVersion: Int get() = raw["format_version"]?.jsonPrimitive?.intOrNull ?: 1
        val banks: JsonObject get() = (raw["banks"] as? JsonObject) ?: JsonObject(emptyMap())
        val bankLetters: List<Char> get() = banks.keys.mapNotNull { it.singleOrNull() }.filter { it in P6.BANKS }.sorted()
        val patternSlots: List<PatternSlot>
            get() = ((raw["patterns"] as? JsonObject)?.get("slots") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(PatternSlot::fromLabel) } ?: emptyList()
        val patternBanks: Set<Int> get() = patternSlots.map { it.bank }.toSet()

        fun padsIn(bank: Char): Int =
            ((banks[bank.toString()] as? JsonObject)?.get("pads") as? JsonObject)?.values?.count { it !is JsonNull } ?: 0
    }

    fun isPreset(dir: VNode): Boolean = dir.isDirectory && dir.child(MANIFEST) != null

    fun readManifest(dir: VNode): Manifest? = try {
        val obj = json.parseToJsonElement(String(dir.child(MANIFEST)!!.readBytes(), Charsets.UTF_8)).jsonObject
        if ("banks" in obj) Manifest(obj) else null
    } catch (_: Exception) {
        null
    }

    /** Preset folders directly inside [parent], newest name order. */
    fun list(parent: VNode): List<Pair<VNode, Manifest>> =
        parent.children().filter { it.isDirectory }.mapNotNull { d -> readManifest(d)?.let { d to it } }
            .sortedBy { it.first.name.lowercase() }

    class SaveResult(val folder: VNode, val failures: List<String>, val problems: List<String>)

    /**
     * Saves [banks] (and [patternBanks]) of [project] into parent/[name].
     * Saving over an existing preset replaces only the checked banks.
     */
    fun save(parent: VNode, name: String, project: Project, banks: Collection<Char>, patternBanks: Collection<Int>): SaveResult {
        val dir = parent.dir(name)
        val existing = readManifest(dir)?.raw
        val banksData = LinkedHashMap<String, JsonElement>()
        (existing?.get("banks") as? JsonObject)?.forEach { (k, v) ->
            // Banks that were not selected stay, as long as their folder does.
            val letter = k.singleOrNull()
            if (letter != null && letter !in banks && dir.childIgnoreCase("BANK_$k")?.isDirectory == true) banksData[k] = v
        }
        val failures = ArrayList<String>()
        for (bank in banks.sorted()) {
            dir.childIgnoreCase("BANK_$bank")?.deleteRecursively()
            val bankDir = dir.createDirectory("BANK_$bank")
            val bs = project.bank(bank)
            val pads = LinkedHashMap<String, JsonElement>()
            for (pad in P6.PADS) {
                val st = bs.pad(pad)
                val src = st?.let { File(it.filepath) }
                if (st == null || src == null || !src.isFile) {
                    pads[pad.toString()] = JsonNull; continue
                }
                try {
                    val padDir = bankDir.createDirectory("PAD_$pad")
                    val written = padDir.writeFile(src.name, src, "audio/wav")
                    if (written.length != src.length()) throw java.io.IOException("copied ${written.length} of ${src.length()} bytes")
                    PadAudio.prmFor(src)?.let { prm ->
                        val text = String(prm.readBytes(), Charsets.ISO_8859_1)
                        val out = Prm.retargetPhrase(text, bank, pad) ?: text
                        padDir.writeFile(src.name.substringBeforeLast('.') + ".PRM", out.toByteArray(Charsets.ISO_8859_1))
                    }
                    pads[pad.toString()] = buildJsonObject {
                        put("filepath", "BANK_$bank/PAD_$pad/${src.name}")
                        put("target_rate", st.target_rate)
                        put("pitch_cents", st.pitch_cents)
                        put("mono", st.mono)
                        put("display_name", st.display_name ?: src.name)
                        put("wavetable", st.wavetable?.let { json.encodeToJsonElement(it) } ?: JsonNull)
                        put("wt_patch", st.wt_patch)
                        put("wt_poly", st.wt_poly)
                    }
                } catch (e: Exception) {
                    failures.add("BANK_$bank/PAD_$pad: ${st.name} - ${e.message}")
                    pads[pad.toString()] = JsonNull
                }
            }
            banksData[bank.toString()] = buildJsonObject {
                put("pads", JsonObject(pads))
                put("force_mono", bs.forceMono)
            }
        }
        val slots = savePatterns(dir, existing, project.patterns, patternBanks.toSet(), failures)
        val manifest = buildJsonObject {
            put("format_version", FORMAT_VERSION)
            put("banks", JsonObject(banksData.toSortedMap()))
            if (slots.isNotEmpty()) put("patterns", buildJsonObject { put("slots", JsonArray(slots.map { JsonPrimitive(it.label) })) })
        }
        dir.writeFile(MANIFEST, prettyJson(manifest).toByteArray(Charsets.UTF_8), "application/json")
        return SaveResult(dir, failures, verify(dir))
    }

    private fun prettyJson(e: JsonElement): String =
        kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), e)

    private fun savePatterns(
        dir: VNode, existing: JsonObject?, patterns: Map<PatternSlot, P6Pattern>, banks: Set<Int>, failures: MutableList<String>,
    ): List<PatternSlot> {
        val patDir = dir.childIgnoreCase(PATTERN_DIR)
        val kept = existing?.let { Manifest(it).patternSlots }.orEmpty()
            .filter { it.bank !in banks && patDir?.child(it.fileName) != null }
        val written = ArrayList<PatternSlot>()
        if (banks.isNotEmpty()) {
            val pd = dir.dir(PATTERN_DIR)
            for (slot in PatternSlot.ALL) {
                if (slot.bank !in banks) continue
                val p = patterns[slot]
                try {
                    if (p == null) {
                        pd.child(slot.fileName)?.delete(); continue
                    }
                    pd.writeFile(slot.fileName, p.toBytes())
                    written.add(slot)
                } catch (e: Exception) {
                    failures.add("Pattern ${slot.label} - ${e.message}")
                }
            }
        }
        val all = (kept + written).toSortedSet().toList()
        if (all.isEmpty()) dir.childIgnoreCase(PATTERN_DIR)?.let { if (it.children().isEmpty()) it.delete() }
        return all
    }

    /** Problems that would make the preset fail somewhere else; empty when sound. */
    fun verify(dir: VNode): List<String> {
        val m = readManifest(dir) ?: return listOf("$MANIFEST is missing or unreadable.")
        val problems = ArrayList<String>()
        if (m.formatVersion > FORMAT_VERSION) problems.add("Written in format version ${m.formatVersion}; this build understands up to $FORMAT_VERSION.")
        for ((bank, be) in m.banks) {
            val pads = (be as? JsonObject)?.get("pads") as? JsonObject ?: continue
            for ((padKey, entry) in pads) {
                val obj = entry as? JsonObject ?: continue
                val where = "BANK_$bank/PAD_$padKey"
                val rel = obj["filepath"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (rel.isEmpty()) { problems.add("$where: no file recorded."); continue }
                if (rel.startsWith("/") || rel.getOrNull(1) == ':' || ".." in rel.split("/")) {
                    problems.add("$where: path is not relative to the preset folder ($rel)."); continue
                }
                val node = resolve(dir, rel)
                if (node == null) { problems.add("$where: $rel is missing."); continue }
                if (node.length == 0L) { problems.add("$where: $rel is empty (0 bytes)."); continue }
                try {
                    val info = node.openInput().use { Wav.readInfo(it.buffered()) }
                    if (info.frames == 0) problems.add("$where: $rel contains no audio frames.")
                } catch (e: Exception) {
                    problems.add("$where: $rel is not a readable WAV (${e.message})."); continue
                }
                if (obj["wavetable"] is JsonObject) {
                    val parent = resolveParent(dir, rel)
                    val stem = rel.substringAfterLast('/').substringBeforeLast('.')
                    if (parent?.children()?.none { it.name.equals("$stem.PRM", true) } != false) {
                        problems.add("$where: wavetable pad without its .PRM - the P-6 cannot loop a segment without it.")
                    }
                }
            }
        }
        val patDir = dir.childIgnoreCase(PATTERN_DIR)
        for (slot in m.patternSlots) {
            val f = patDir?.child(slot.fileName)
            if (f == null) problems.add("Pattern ${slot.label}: $PATTERN_DIR/${slot.fileName} is missing.")
        }
        return problems
    }

    private fun resolve(dir: VNode, rel: String): VNode? {
        var cur: VNode = dir
        for (part in rel.split('/').filter { it.isNotEmpty() }) cur = cur.child(part) ?: cur.childIgnoreCase(part) ?: return null
        return cur
    }

    private fun resolveParent(dir: VNode, rel: String): VNode? {
        val parts = rel.split('/').filter { it.isNotEmpty() }
        return if (parts.size <= 1) dir else resolve(dir, parts.dropLast(1).joinToString("/"))
    }

    class LoadResult(val project: Project, val missing: List<String>, val patternProblems: List<String>, val patternsLoaded: Int)

    /**
     * Loads [banks] from the preset into [project], copying each sample (and
     * its .PRM) into [store]. With [targetOverride] a single bank lands in
     * that bank instead of its own letter.
     */
    fun load(
        dir: VNode, manifest: Manifest, project: Project, store: SampleStore,
        banks: Collection<Char>, patternBanks: Collection<Int>, targetOverride: Char? = null,
    ): LoadResult {
        var p = project
        val missing = ArrayList<String>()
        for (bank in banks) {
            val be = manifest.banks[bank.toString()] as? JsonObject ?: continue
            val pads = be["pads"] as? JsonObject ?: JsonObject(emptyMap())
            val target = targetOverride ?: bank
            val list = MutableList<PadState?>(P6.PADS.size) { null }
            for (pad in P6.PADS) {
                val entry = pads[pad.toString()] as? JsonObject ?: continue
                val rel = entry["filepath"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val node = if (rel.isNotEmpty()) resolve(dir, rel) else null
                if (node == null) {
                    missing.add("BANK_$target/PAD_$pad (from BANK_$bank)"); continue
                }
                try {
                    val local = store.newFile(node.name, "pre", "." + node.name.substringAfterLast('.', "wav"))
                    node.openInput().use { input -> local.outputStream().use { input.copyTo(it) } }
                    val parent = resolveParent(dir, rel)
                    val stem = node.name.substringBeforeLast('.')
                    parent?.children()?.firstOrNull { it.name.equals("$stem.PRM", true) }?.let { prm ->
                        store.attachPrm(local, prm.readBytes())
                    }
                    val wt = entry["wavetable"]?.takeIf { it is JsonObject }?.let {
                        runCatching { json.decodeFromJsonElement<WavetableState>(it) }.getOrNull()
                    }
                    list[pad - 1] = PadState(
                        filepath = local.absolutePath,
                        target_rate = entry["target_rate"]?.jsonPrimitive?.intOrNull ?: 44100,
                        pitch_cents = entry["pitch_cents"]?.jsonPrimitive?.intOrNull ?: 0,
                        mono = entry["mono"]?.jsonPrimitive?.booleanOrNull ?: false,
                        display_name = entry["display_name"]?.jsonPrimitive?.contentOrNull,
                        wavetable = wt,
                        wt_patch = entry["wt_patch"]?.jsonPrimitive?.contentOrNull?.takeIf { it in Prm.TEMPLATES } ?: "Init",
                        wt_poly = entry["wt_poly"]?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                } catch (e: Exception) {
                    missing.add("BANK_$target/PAD_$pad: ${e.message}")
                }
            }
            val fm = be["force_mono"]?.jsonPrimitive?.booleanOrNull ?: false
            p = p.withBank(target, BankState(list, fm))
        }
        var loaded = 0
        val problems = ArrayList<String>()
        if (patternBanks.isNotEmpty()) {
            val pb = patternBanks.toSet()
            val pats = LinkedHashMap(p.patterns)
            val have = manifest.patternSlots.toSet()
            val patDir = dir.childIgnoreCase(PATTERN_DIR)
            for (slot in PatternSlot.ALL) {
                if (slot.bank !in pb) continue
                pats.remove(slot)
                if (slot !in have) continue
                try {
                    pats[slot] = P6Pattern.fromBytes(patDir!!.child(slot.fileName)!!.readBytes())
                    loaded++
                } catch (e: Exception) {
                    problems.add("Pattern ${slot.label}: ${e.message ?: "unreadable"}")
                }
            }
            p = p.copy(patterns = pats.toSortedMap(), patternsDirty = true, patternSource = p.patternSource ?: "preset ${dir.name}")
        }
        return LoadResult(p, missing, problems, loaded)
    }

    @Suppress("unused")
    private fun unusedJson(e: JsonElement) = e.jsonArray
}
