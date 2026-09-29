package io.github.pyp6.core.device

import io.github.pyp6.core.P6
import io.github.pyp6.core.io.SafeName
import io.github.pyp6.core.io.VNode
import io.github.pyp6.core.model.PadAudio
import io.github.pyp6.core.model.PadState
import io.github.pyp6.core.model.Project
import io.github.pyp6.core.pattern.P6Pattern
import io.github.pyp6.core.pattern.PatternSlot
import java.io.File

/**
 * The P-6's USB drive in storage mode. It presents four top-level folders,
 * directly on the drive or inside a P-6 folder on it:
 *
 *  - IMPORT/BANK_x/PAD_n/  where samples go to the device,
 *  - EXPORT/BANK_x/PAD_n/  where it writes a bank when asked to export,
 *  - BACKUP/               where it writes its patterns (P6_PTN1-01.PRM ...),
 *  - RESTORE/              where it reads patterns to restore.
 *
 * Every function takes the folder the user granted access to, which may be
 * the drive, the P-6 folder or one of the four folders itself.
 */
object P6Drive {
    const val IMPORT = "IMPORT"
    const val EXPORT = "EXPORT"
    const val BACKUP = "BACKUP"
    const val RESTORE = "RESTORE"

    private fun isAudio(name: String) =
        (name.lowercase().endsWith(".wav") || name.lowercase().endsWith(".mp3")) && !SafeName.isSidecar(name)

    /** [folder] under [root]: root itself when it IS that folder, else root/folder or root/P-6/folder. */
    fun find(root: VNode, folder: String): VNode? {
        if (root.name.equals(folder, ignoreCase = true)) return root
        root.childIgnoreCase(folder)?.takeIf { it.isDirectory }?.let { return it }
        val nested = root.childIgnoreCase("P-6")?.takeIf { it.isDirectory } ?: return null
        return nested.childIgnoreCase(folder)?.takeIf { it.isDirectory }
    }

    /** Which of the device folders are present - tells the user what mode the P-6 is in. */
    fun describe(root: VNode): Set<String> =
        listOf(IMPORT, EXPORT, BACKUP, RESTORE).filter { find(root, it) != null }.toSet()

    // ------------------------------------------------------------ IMPORT

    data class ExportReport(val copied: Int, val skipped: Int, val errors: List<String>)

    /**
     * Writes one bank to IMPORT/BANK_x/PAD_n: each pad converted to its
     * rate/pitch/mono, stale files in the pad folder removed, and the .PRM
     * (retargeted to this slot) written beside it when it still applies.
     */
    fun exportBank(
        importRoot: VNode, project: Project, bank: Char, includePrm: Boolean, tempDir: File,
        progress: (String) -> Unit = {},
    ): ExportReport {
        val bankState = project.bank(bank)
        val bankDir = importRoot.dir("BANK_$bank")
        var copied = 0
        var skipped = 0
        val errors = ArrayList<String>()
        for (pad in P6.PADS) {
            val state = bankState.pad(pad)
            if (state == null || !File(state.filepath).isFile) {
                skipped++; continue
            }
            progress("Bank $bank … pad $pad")
            try {
                val padDir = bankDir.dir("PAD_$pad")
                val mono = bankState.forceMono || state.mono
                val exportFile = PadAudio.exportFile(state, mono, tempDir)
                val destName = SafeName.baseName(
                    if (exportFile == File(state.filepath)) state.name else exportFile.name
                ) + ".wav"
                val prmText = PadAudio.prmTextForExport(state, bank, pad, includePrm)
                val prmName = destName.substringBeforeLast('.') + ".PRM"
                // Anything else in the pad folder would compete with the new
                // sample on the device.
                for (c in padDir.children()) {
                    if (c.isDirectory) continue
                    if (c.name == destName || (prmText != null && c.name == prmName)) continue
                    c.delete()
                }
                if (prmText != null) padDir.writeFile(prmName, prmText.toByteArray(Charsets.ISO_8859_1), "application/octet-stream")
                val written = padDir.writeFile(destName, exportFile, "audio/wav")
                val want = exportFile.length()
                if (written.length in 1 until want) {
                    written.delete()
                    throw java.io.IOException("copied ${written.length} of $want bytes - the P-6 may be full or disconnected")
                }
                copied++
            } catch (e: Exception) {
                errors.add("PAD_$pad: ${e.message ?: e.javaClass.simpleName}")
                skipped++
            } finally {
                tempDir.listFiles()?.forEach { it.delete() }
            }
        }
        return ExportReport(copied, skipped, errors)
    }

    /** Audio files already on the device for one pad (what Eject can also delete). */
    fun padFiles(importRoot: VNode, bank: Char, pad: Int): List<VNode> {
        val padDir = importRoot.childIgnoreCase("BANK_$bank")?.childIgnoreCase("PAD_$pad") ?: return emptyList()
        return padDir.children().filter { !it.isDirectory && isAudio(it.name) }
    }

    /**
     * Everything of one pad in IMPORT that goes when the pad is cleared: the
     * sample, its .PRM (a .PRM left alone would still be on the device with
     * no sample) and macOS "._" leftovers.
     */
    fun padFilesToDelete(importRoot: VNode, bank: Char, pad: Int): List<VNode> {
        val padDir = importRoot.childIgnoreCase("BANK_$bank")?.childIgnoreCase("PAD_$pad") ?: return emptyList()
        return padDir.children().filter {
            !it.isDirectory && (isAudio(it.name) || SafeName.isSidecar(it.name) || it.name.lowercase().endsWith(".prm"))
        }
    }

    /** Every pad file in IMPORT (samples, .PRM files, leftovers), as "BANK_A/PAD_1/name.wav" -> node. */
    fun importFiles(importRoot: VNode): List<Pair<String, VNode>> {
        val out = ArrayList<Pair<String, VNode>>()
        for (bank in P6.BANKS) for (pad in P6.PADS) {
            for (f in padFilesToDelete(importRoot, bank, pad)) out.add("BANK_$bank/PAD_$pad/${f.name}" to f)
        }
        return out
    }

    /** Deletes every sample in IMPORT with its .PRM. Returns (files deleted, errors). */
    fun wipeImport(importRoot: VNode): Pair<Int, List<String>> {
        var n = 0
        val errors = ArrayList<String>()
        for ((label, f) in importFiles(importRoot)) {
            if (f.delete()) n++ else errors.add(label)
        }
        return n to errors
    }

    // ------------------------------------------------------------ EXPORT

    /** One pad's sample in an export folder: flat PAD_1.WAV or PAD_1/<name>.wav. */
    fun findPadFile(folder: VNode, pad: Int): VNode? {
        val kids = folder.children()
        kids.firstOrNull { !it.isDirectory && it.name.equals("PAD_$pad.wav", ignoreCase = true) }?.let { return it }
        val sub = kids.firstOrNull { it.isDirectory && it.name.equals("PAD_$pad", ignoreCase = true) } ?: return null
        return sub.children().filter { !it.isDirectory && it.name.lowercase().endsWith(".wav") && !SafeName.isSidecar(it.name) }
            .minByOrNull { it.name }
    }

    /** The .PRM that belongs to [sample] inside [folder], if any. */
    fun prmBeside(folder: VNode, sample: VNode): VNode? {
        val stem = sample.name.substringBeforeLast('.')
        return folder.children().firstOrNull {
            !it.isDirectory && it.name.substringBeforeLast('.') == stem && it.name.substringAfterLast('.').equals("PRM", true)
        }
    }

    private fun hasPads(folder: VNode) = P6.PADS.any { findPadFile(folder, it) != null }

    data class ExportFolder(val folder: VNode, val note: String)

    /**
     * The folder the exported pad samples actually live in, walking down
     * through EXPORT and BANK_x as needed. With several banks present the
     * one matching [preferredBank] wins.
     */
    fun resolveExportFolder(chosen: VNode, preferredBank: Char?): ExportFolder? {
        if (hasPads(chosen)) return ExportFolder(chosen, "")
        var base = chosen
        var note = ""
        find(chosen, EXPORT)?.let { e ->
            if (e !== chosen) {
                base = e; note = " (found via the EXPORT folder)"
                if (hasPads(e)) return ExportFolder(e, note)
            }
        }
        val banks = base.children().filter { it.isDirectory && it.name.uppercase().startsWith("BANK_") && hasPads(it) }
            .sortedBy { it.name }
        if (banks.isEmpty()) return null
        if (banks.size == 1) return ExportFolder(banks[0], "$note (from ${banks[0].name})".trim())
        if (preferredBank != null) {
            banks.firstOrNull { it.name.equals("BANK_$preferredBank", ignoreCase = true) }?.let {
                return ExportFolder(it, "$note (from ${it.name}, of ${banks.size} banks present)".trim())
            }
        }
        return ExportFolder(banks[0], "$note (using ${banks[0].name}; folder contains ${banks.joinToString(", ") { it.name }})".trim())
    }

    // ------------------------------------------------------------ patterns

    /** {slot: file} in [folder], or one level down (BACKUP first) when it holds none. */
    fun findPatternFiles(folder: VNode): Pair<VNode, Map<PatternSlot, VNode>> {
        fun scan(d: VNode): Map<PatternSlot, VNode> {
            val found = sortedMapOf<PatternSlot, VNode>()
            for (c in d.children().sortedBy { it.name }) {
                if (c.isDirectory) continue
                val slot = PatternSlot.fromFileName(c.name) ?: continue
                if (slot !in found) found[slot] = c
            }
            return found
        }
        val direct = scan(folder)
        if (direct.isNotEmpty()) return folder to direct
        val subs = folder.children().filter { it.isDirectory }.sortedWith(
            compareBy<VNode>({ !it.name.equals(BACKUP, true) }, { it.name })
        )
        for (s in subs) {
            val f = scan(s)
            if (f.isNotEmpty()) return s to f
        }
        // The P-6 folder itself may sit one more level down.
        find(folder, BACKUP)?.let { b -> val f = scan(b); if (f.isNotEmpty()) return b to f }
        return folder to emptyMap()
    }

    fun readPatterns(files: Map<PatternSlot, VNode>): Pair<Map<PatternSlot, P6Pattern>, List<String>> {
        val out = sortedMapOf<PatternSlot, P6Pattern>()
        val errors = ArrayList<String>()
        for ((slot, f) in files) {
            try {
                out[slot] = P6Pattern.fromBytes(f.readBytes())
            } catch (e: Exception) {
                errors.add("${f.name}: ${e.message}")
            }
        }
        return out to errors
    }

    data class PatternSavePlan(val toWrite: Int, val overwrite: Int, val stale: Int, val emptySlots: Int)

    fun planPatternSave(target: VNode, patterns: Map<PatternSlot, P6Pattern>): PatternSavePlan {
        val existing = existingPatternFiles(target)
        val stale = existing.filterKeys { it !in patterns }.values.sumOf { it.size }
        val overwrite = patterns.keys.count { it in existing }
        return PatternSavePlan(patterns.size, overwrite, stale, PatternSlot.ALL.size - patterns.size)
    }

    private fun existingPatternFiles(target: VNode): Map<PatternSlot, List<VNode>> {
        val out = HashMap<PatternSlot, MutableList<VNode>>()
        for (c in target.children()) {
            if (c.isDirectory) continue
            val s = PatternSlot.fromFileName(c.name) ?: continue
            out.getOrPut(s) { ArrayList() }.add(c)
        }
        return out
    }

    /**
     * Writes every pattern as P6_PTN<b>-<nn>.PRM into [target]. Pattern files
     * there for slots that are now empty are removed - a restore would
     * otherwise put them back. Returns the errors.
     */
    fun savePatterns(target: VNode, patterns: Map<PatternSlot, P6Pattern>): List<String> {
        val errors = ArrayList<String>()
        for ((slot, files) in existingPatternFiles(target)) {
            for (f in files) {
                if (slot !in patterns || f.name != slot.fileName) {
                    if (!f.delete()) errors.add("${f.name}: could not delete")
                }
            }
        }
        for ((slot, p) in patterns.toSortedMap()) {
            try {
                target.writeFile(slot.fileName, p.toBytes())
            } catch (e: Exception) {
                errors.add("${slot.fileName}: ${e.message}")
            }
        }
        return errors
    }

    @Suppress("unused")
    private fun padStateName(s: PadState) = s.name
}
