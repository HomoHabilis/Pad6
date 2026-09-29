package io.github.pyp6.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.pyp6.app.PyP6Application
import io.github.pyp6.app.data.SafNode
import io.github.pyp6.app.data.WaveCache
import io.github.pyp6.core.P6
import io.github.pyp6.core.audio.Audio
import io.github.pyp6.core.audio.Wav
import io.github.pyp6.core.device.P6Drive
import io.github.pyp6.core.io.FileNode
import io.github.pyp6.core.io.VNode
import io.github.pyp6.core.model.BankState
import io.github.pyp6.core.model.PadAudio
import io.github.pyp6.core.model.PadRef
import io.github.pyp6.core.model.PadState
import io.github.pyp6.core.model.Project
import io.github.pyp6.core.model.WavetableState
import io.github.pyp6.core.model.WtConfig
import io.github.pyp6.core.pattern.P6Pattern
import io.github.pyp6.core.pattern.PatternRender
import io.github.pyp6.core.pattern.PatternSlot
import io.github.pyp6.core.preset.Presets
import io.github.pyp6.core.prm.Prm
import io.github.pyp6.core.wavetable.WaveEntry
import io.github.pyp6.core.wavetable.WaveLibrary
import io.github.pyp6.core.wavetable.WtBuildResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiMessage(val text: String, val error: Boolean = false)

/** A dialog with a title and text that the user dismisses. */
data class Alert(val title: String, val text: String)

/** What the pattern preview is doing: which slot, how long a step is, how many steps. */
/** [frames]: one pass of the pattern (the buffer holds a lead-in pass plus the looping one). */
data class PatternPreview(val slot: PatternSlot, val stepSeconds: Double, val steps: Int, val frames: Int)

/**
 * The app's state and everything the screens can do to it. Screens only
 * read StateFlows and call these methods; anything slow runs off the main
 * thread with [busy] set so the UI can show progress.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val c = (app as PyP6Application).container
    private val repo = c.repository

    val project: StateFlow<Project> = repo.project
    val canUndo = repo.canUndo
    val canRedo = repo.canRedo
    val settings = c.settings.state
    val device = c.device.state
    val playback = c.player.state

    private val _bank = MutableStateFlow(c.settings.value.lastBank.takeIf { it in P6.BANKS } ?: 'A')
    val currentBank: StateFlow<Char> = _bank.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private val _alert = MutableStateFlow<Alert?>(null)
    val alert: StateFlow<Alert?> = _alert.asStateFlow()

    private val _selectedPattern = MutableStateFlow<PatternSlot?>(null)
    val selectedPattern: StateFlow<PatternSlot?> = _selectedPattern.asStateFlow()

    /** The pad the user last picked in the overview / pattern map. */
    private val _focusPad = MutableStateFlow<PadRef?>(null)
    val focusPad: StateFlow<PadRef?> = _focusPad.asStateFlow()

    /** "pattern" or "pad": which selection drives the highlights (the one picked last). */
    private val _focus = MutableStateFlow("pattern")
    val focus: StateFlow<String> = _focus.asStateFlow()

    private val _preview = MutableStateFlow<PatternPreview?>(null)
    val preview: StateFlow<PatternPreview?> = _preview.asStateFlow()

    fun message(text: String, error: Boolean = false) {
        _messages.tryEmit(UiMessage(text, error))
    }

    fun showAlert(title: String, text: String) {
        _alert.value = Alert(title, text)
    }

    fun dismissAlert() {
        _alert.value = null
    }

    /** Runs [block] off the main thread with a progress text, reporting failures. */
    private fun work(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = label
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                message(e.message ?: e.javaClass.simpleName, error = true)
            } finally {
                _busy.value = null
            }
        }
    }

    // ------------------------------------------------------------ banks & pads

    fun selectBank(b: Char) {
        _bank.value = b
        c.settings.update { it.copy(lastBank = b) }
    }

    fun undo() {
        c.player.stop()
        if (repo.undo()) message("Undone.")
    }

    fun redo() {
        c.player.stop()
        if (repo.redo()) message("Redone.")
    }

    /**
     * Loads [uris] onto [ref]; several files fill the following pads of the
     * bank in order, like dropping several files on the desktop app.
     */
    fun loadSamples(ref: PadRef, uris: List<Uri>) {
        if (uris.isEmpty()) return
        work("Loading …") {
            val loaded = ArrayList<Pair<PadRef, PadState>>()
            val failed = ArrayList<String>()
            var pad = ref.pad
            for (uri in uris) {
                if (pad > P6.PADS.size) break
                val name = c.importer.displayName(uri)
                _busy.value = "Loading $name"
                try {
                    val (file, converted) = c.importer.import(uri)
                    val state = c.store.newPadState(file, if (converted) "$name" else name)
                    loaded.add(PadRef(ref.bank, pad) to state)
                    pad++
                } catch (e: Exception) {
                    failed.add("$name: ${e.message ?: "could not be read"}")
                }
            }
            if (loaded.isNotEmpty()) {
                repo.update { p -> loaded.fold(p) { acc, (r, s) -> acc.withPad(r, s) } }
                val extra = uris.size - loaded.size - failed.size
                message(
                    if (loaded.size == 1) "${loaded[0].second.name} loaded on ${loaded[0].first.label}."
                    else "${loaded.size} samples loaded from ${loaded.first().first.label}." +
                        if (extra > 0) " $extra did not fit in the bank." else ""
                )
            }
            if (failed.isNotEmpty()) showAlert("Some files could not be loaded", failed.joinToString("\n"))
        }
    }

    fun setPad(ref: PadRef, transform: (PadState) -> PadState) {
        repo.update { p -> p.pad(ref)?.let { p.withPad(ref, transform(it)) } ?: p }
    }

    fun setRate(ref: PadRef, rate: Int) = setPad(ref) { it.copy(target_rate = rate) }

    fun setPitch(ref: PadRef, cents: Int) =
        setPad(ref) { it.copy(pitch_cents = cents.coerceIn(P6.PITCH_MIN_CENTS, P6.PITCH_MAX_CENTS)) }

    fun setMono(ref: PadRef, on: Boolean) = setPad(ref) { it.copy(mono = on) }

    fun setForceMono(bank: Char, on: Boolean) = repo.update { p -> p.withBank(bank, p.bank(bank).copy(forceMono = on)) }

    /** Changes a wavetable pad's init patch or poly switch: only its .PRM is rewritten. */
    fun setWavetableVoice(ref: PadRef, patch: String? = null, poly: Boolean? = null) {
        val st = project.value.pad(ref) ?: return
        val wt = st.wavetable ?: return
        var np = patch ?: st.wt_patch
        var poly2 = poly ?: st.wt_poly
        if (patch == "Pad") poly2 = true            // the Pad voicing is polyphonic by design
        if (np !in Prm.TEMPLATES) np = "Init"
        val updated = st.copy(wt_patch = np, wt_poly = poly2)
        writeWavetablePrm(updated, ref, wt)
        repo.update { it.withPad(ref, updated) }
    }

    private fun writeWavetablePrm(st: PadState, ref: PadRef, wt: WavetableState) {
        runCatching {
            File(st.filepath.substringBeforeLast('.') + ".PRM").writeText(
                Prm.render(ref.bank, ref.pad, 0, wt.meta.L, wt.meta.total_frames, st.wt_patch, st.wt_poly)
            )
        }
    }

    /** Samples already on the P-6 for this pad (what Eject can also delete). */
    suspend fun deviceFilesFor(ref: PadRef): List<String> = withContext(Dispatchers.IO) {
        val root = c.device.root() ?: return@withContext emptyList()
        val import = P6Drive.find(root, P6Drive.IMPORT) ?: return@withContext emptyList()
        runCatching { P6Drive.padFiles(import, ref.bank, ref.pad).map { it.name } }.getOrDefault(emptyList())
    }

    fun ejectPad(ref: PadRef, alsoFromDevice: Boolean) {
        c.player.stop()
        repo.update { it.withPad(ref, null) }
        if (alsoFromDevice) work("Deleting from the P-6 …") {
            val import = c.device.root()?.let { P6Drive.find(it, P6Drive.IMPORT) }
                ?: throw IllegalStateException("The P-6 drive is not connected.")
            val files = P6Drive.padFiles(import, ref.bank, ref.pad)
            val deleted = files.count { it.delete() }
            message("${ref.label} cleared; $deleted file(s) deleted from the P-6.")
        } else message("${ref.label} cleared.")
    }

    fun swapPads(a: PadRef, b: PadRef) {
        if (a == b) return
        c.player.stop()
        var changed = 0
        repo.update { p -> p.swapPads(a, b, settings.value.patternSync).also { changed = it.second }.first }
        message("Swapped ${a.label} and ${b.label}." + patternsNote(changed))
    }

    fun swapBanks(a: Char, b: Char) {
        if (a == b) return
        c.player.stop()
        var changed = 0
        repo.update { p -> p.swapBanks(a, b, settings.value.patternSync).also { changed = it.second }.first }
        message("Swapped banks $a and $b." + patternsNote(changed))
    }

    /** Copies or moves bank [source] onto [target]. */
    fun transferBank(source: Char, target: Char, move: Boolean) {
        if (source == target) return
        c.player.stop()
        work(if (move) "Moving bank …" else "Copying bank …") {
            var changed = 0
            repo.update { p ->
                val src = p.bank(source)
                val copy = if (move) src else src.copy(pads = src.pads.map { it?.let(c.store::duplicateForCopy) })
                var np = p.withBank(target, copy)
                if (move) {
                    val (synced, n) = np.syncPatterns(P6.PADS.map { PadRef(source, it) to PadRef(target, it) }, false,
                        settings.value.patternSync)
                    np = synced.withBank(source, BankState())
                    changed = n
                }
                np
            }
            if (move) selectBank(target)
            message("Bank $source ${if (move) "moved" else "copied"} to $target." + patternsNote(changed))
        }
    }

    fun clearBanks(banks: Collection<Char>) {
        if (banks.isEmpty()) return
        c.player.stop()
        repo.update { it.clearBanks(banks) }
        message("${if (banks.size == 1) "Bank" else "Banks"} ${banks.sorted().joinToString(", ")} cleared.")
    }

    private fun patternsNote(changed: Int) =
        if (changed > 0) " $changed pattern${if (changed != 1) "s" else ""} updated." else ""

    /** Replaces a pad's file with an edited version, keeping its settings (one undo step). */
    fun applyEditedFile(ref: PadRef, file: File, displayName: String) {
        c.player.stop()
        setPad(ref) { it.copy(filepath = file.absolutePath, display_name = displayName, from_sync = false) }
        message("${ref.label} updated.")
    }

    /** Puts a freshly built file on a pad as a new sample (rate detected, pitch 0). */
    fun applyNewFile(ref: PadRef, file: File, displayName: String) {
        c.player.stop()
        repo.update { it.withPad(ref, c.store.newPadState(file, displayName)) }
        message("${displayName} is on ${ref.label}.")
    }

    fun applyWavetable(ref: PadRef, result: WtBuildResult, config: WtConfig) {
        c.player.stop()
        val file = c.store.newFile("wavetable_${ref.label}", "wt", ".WAV")
        Wav.writePcm16Mono(result.pcm, 44100, file)
        val old = project.value.pad(ref)
        val st = PadState(
            filepath = file.absolutePath, target_rate = 44100, pitch_cents = 0, mono = false,
            display_name = "Synth Mode", wavetable = WavetableState(config, result.meta),
            wt_patch = old?.wt_patch?.takeIf { old.isWavetable } ?: "Init",
            wt_poly = old?.wt_poly?.takeIf { old.isWavetable } ?: false,
        )
        writeWavetablePrm(st, ref, st.wavetable!!)
        repo.update { it.withPad(ref, st) }
        message("Wavetable built on ${ref.label}.")
    }

    // ------------------------------------------------------------ playback

    fun padPlaybackId(ref: PadRef) = "pad:${ref.label}"

    fun togglePad(ref: PadRef) {
        val id = padPlaybackId(ref)
        if (c.player.isPlaying(id)) {
            c.player.stop(); return
        }
        stopPatternPreview()
        val st = project.value.pad(ref) ?: return
        val mono = project.value.effectiveMono(ref)
        viewModelScope.launch {
            val audio = withContext(Dispatchers.Default) { runCatching { PadAudio.previewAudio(st, mono) }.getOrNull() }
            if (audio == null) message("${ref.label} cannot be played.", error = true)
            else c.player.play(id, audio)
        }
    }

    fun play(id: String, audio: Audio, loop: Boolean = false, startFrac: Double = 0.0) {
        stopPatternPreview()
        c.player.play(id, audio, loop, startFrac)
    }

    fun stopPlayback() {
        c.player.stop()
        _preview.value = null
    }

    // ------------------------------------------------------------ device

    fun deviceGrantIntent(): Intent? = c.device.grantIntent()

    fun grantDevice(uri: Uri) = c.device.grant(uri)

    fun forgetDevice() = c.device.forget()

    fun refreshDevice() = viewModelScope.launch(Dispatchers.IO) { c.device.refresh() }

    private fun deviceRoot(): VNode = c.device.root()
        ?: throw IllegalStateException("The P-6 drive is not connected. Plug it in and start it in storage mode.")

    fun bankBytes(bank: Char): Long = PadAudio.bankBytes(project.value.bank(bank))

    /** Banks → P6: writes the chosen banks into IMPORT/BANK_x/PAD_n. */
    fun sendBanks(banks: List<Char>, includePrm: Boolean) {
        if (banks.isEmpty()) return
        c.player.stop()
        c.settings.update { it.copy(includePrm = includePrm) }
        work("Sending to the P-6 …") {
            val root = deviceRoot()
            val import = P6Drive.find(root, P6Drive.IMPORT) ?: throw IllegalStateException(
                "No IMPORT folder on the drive. Start the P-6 in storage mode: switch it off, hold [●] REC and switch it on.")
            var copied = 0
            var skipped = 0
            val errors = ArrayList<String>()
            for (b in banks.sorted()) {
                val r = P6Drive.exportBank(import, project.value, b, includePrm, c.store.tempDir) { _busy.value = "Sending $it" }
                copied += r.copied; skipped += r.skipped; errors += r.errors.map { "Bank $b: $it" }
            }
            val text = buildString {
                append("$copied sample${if (copied != 1) "s" else ""} written to the P-6 (bank${if (banks.size > 1) "s" else ""} ${banks.sorted().joinToString(", ")}).")
                if (errors.isNotEmpty()) append("\n\nProblems:\n").append(errors.joinToString("\n"))
                append("\n\nNext: eject the drive before unplugging it - pull down the notification shade and tap Eject on the USB drive " +
                    "notification (or Settings › Storage). Then press a pad key on the P-6 and wait until it shows it is done.")
            }
            showAlert(if (errors.isEmpty()) "Sent to the P-6" else "Sent with problems", text)
        }
    }

    /** P6 → Bank: reads a bank the P-6 exported into [target] (replaces it). */
    fun importBank(target: Char, folderUri: Uri? = null) {
        c.player.stop()
        work("Importing from the P-6 …") {
            val root = folderUri?.let { SafNode.fromTree(getApplication(), it) ?: throw IllegalStateException("That folder cannot be read.") }
                ?: deviceRoot()
            val found = P6Drive.resolveExportFolder(root, target) ?: throw IllegalStateException(
                "No pad samples found. The P-6 writes EXPORT/BANK_x/PAD_n/<name>.WAV - it may not have finished writing its export yet.")
            val pads = MutableList<PadState?>(6) { null }
            var n = 0
            for (pad in P6.PADS) {
                val f = P6Drive.findPadFile(found.folder, pad) ?: continue
                _busy.value = "Importing pad $pad of 6"
                val local = c.store.newFile(f.name, "imp_$target$pad", ".wav")
                f.openInput().use { input -> local.outputStream().use { input.copyTo(it) } }
                val parent = found.folder.children().firstOrNull { it.isDirectory && it.name.equals("PAD_$pad", true) } ?: found.folder
                P6Drive.prmBeside(parent, f)?.let { c.store.attachPrm(local, it.readBytes()) }
                pads[pad - 1] = c.store.newPadState(local, f.name)
                n++
            }
            repo.update { it.withBank(target, it.bank(target).copy(pads = pads)) }
            selectBank(target)
            message("Imported $n sample${if (n != 1) "s" else ""} into bank $target ${found.note}".trim())
        }
    }

    suspend fun importFileCount(): Int = withContext(Dispatchers.IO) {
        val import = c.device.root()?.let { P6Drive.find(it, P6Drive.IMPORT) } ?: return@withContext -1
        runCatching { P6Drive.importFiles(import).size }.getOrDefault(-1)
    }

    fun wipeImport() = work("Deleting samples on the P-6 …") {
        val import = P6Drive.find(deviceRoot(), P6Drive.IMPORT) ?: throw IllegalStateException("No IMPORT folder on the drive.")
        val (n, errors) = P6Drive.wipeImport(import)
        if (errors.isEmpty()) message("$n file${if (n != 1) "s" else ""} deleted from the P-6.")
        else showAlert("Some files could not be deleted", errors.joinToString("\n"))
    }

    // ------------------------------------------------------------ patterns

    fun selectPattern(slot: PatternSlot?) {
        _selectedPattern.value = slot
        _focus.value = "pattern"
        val pv = _preview.value
        if (slot != null && pv != null && pv.slot != slot) startPatternPreview(slot)
    }

    fun focusPad(ref: PadRef?) {
        _focusPad.value = ref
        _focus.value = "pad"
    }

    fun setPatternSync(on: Boolean) {
        c.settings.update { it.copy(patternSync = on) }
        message(if (on) "Pad moves now update the patterns." else "Pad moves no longer touch the patterns.")
    }

    private fun loadPatterns(folder: VNode, source: String) {
        val (dir, files) = P6Drive.findPatternFiles(folder)
        if (files.isEmpty()) throw IllegalStateException("No pattern files (P6_PTN1-01.PRM … P6_PTN4-16.PRM) in ${folder.name} or the folders directly inside it.")
        val (loaded, errors) = P6Drive.readPatterns(files)
        stopPatternPreview()
        repo.update { it.copy(patterns = loaded, patternSource = "$source ${dir.name}".trim(), patternsDirty = false) }
        _selectedPattern.value = null
        val missing = PatternSlot.ALL.size - loaded.size
        message("Loaded ${loaded.size} patterns." + if (missing > 0) " $missing slot${if (missing != 1) "s" else ""} had no file." else "")
        if (errors.isNotEmpty()) showAlert("Some patterns could not be read", errors.take(12).joinToString("\n"))
    }

    fun loadPatternsFromDevice() = work("Reading patterns …") {
        val backup = P6Drive.find(deviceRoot(), P6Drive.BACKUP) ?: throw IllegalStateException(
            "No BACKUP folder on the drive. Switch the P-6 off, hold [▶] PLAY and switch it on; wait until the step buttons show it has written its patterns.")
        loadPatterns(backup, "P-6")
    }

    fun loadPatternsFromFolder(uri: Uri) = work("Reading patterns …") {
        val root = SafNode.fromTree(getApplication(), uri) ?: throw IllegalStateException("That folder cannot be read.")
        loadPatterns(root, "")
    }

    suspend fun patternSavePlan(toDevice: Boolean, uri: Uri?): P6Drive.PatternSavePlan? = withContext(Dispatchers.IO) {
        runCatching {
            val target = if (toDevice) c.device.root()?.let { P6Drive.find(it, P6Drive.RESTORE) } else uri?.let { SafNode.fromTree(getApplication(), it) }
            target?.let { P6Drive.planPatternSave(it, project.value.patterns) }
        }.getOrNull()
    }

    fun savePatterns(toDevice: Boolean, uri: Uri?) = work("Writing patterns …") {
        val target = if (toDevice) {
            P6Drive.find(deviceRoot(), P6Drive.RESTORE) ?: throw IllegalStateException(
                "No RESTORE folder on the drive. Switch the P-6 off, hold [●] REC and switch it on.")
        } else SafNode.fromTree(getApplication(), uri ?: return@work) ?: throw IllegalStateException("That folder cannot be written.")
        val pats = project.value.patterns
        val errors = P6Drive.savePatterns(target, pats)
        if (errors.isNotEmpty()) {
            showAlert("Some files were not written", errors.take(12).joinToString("\n")); return@work
        }
        repo.replaceSilently { it.copy(patternsDirty = false) }
        if (toDevice) showAlert("Patterns copied to the P-6",
            "${pats.size} pattern files are in the RESTORE folder.\n\nEject the P-6 drive (notification shade › USB drive › Eject), " +
                "then press [KYBD] on the P-6 to restore them. The step buttons show the progress; it can take around five minutes.")
        else message("Saved ${pats.size} patterns to ${target.name}.")
    }

    fun clearPattern(slot: PatternSlot) {
        val pat = project.value.patterns[slot]
        val new = pat?.cleared() ?: P6Pattern.blank()
        if (new === pat) {
            message("Pattern ${slot.label} is already empty."); return
        }
        stopPatternPreview()
        repo.update { it.copy(patterns = (it.patterns + (slot to new)).toSortedMap(), patternsDirty = true) }
        message(if (pat == null) "Created an empty pattern in ${slot.label}." else "Cleared pattern ${slot.label}; its tempo, length and sound settings are kept.")
    }

    fun swapPatterns(a: PatternSlot, b: PatternSlot) {
        if (a == b) return
        stopPatternPreview()
        val pa = project.value.patterns[a]
        val pb = project.value.patterns[b]
        repo.update { p ->
            val m = p.patterns.toMutableMap()
            if (pb == null) m.remove(a) else m[a] = pb
            if (pa == null) m.remove(b) else m[b] = pa
            p.copy(patterns = m.toSortedMap(), patternsDirty = true)
        }
        if (_selectedPattern.value == a) _selectedPattern.value = b
        else if (_selectedPattern.value == b) _selectedPattern.value = a
        message(if (pb == null) "Moved pattern ${a.label} to ${b.label}." else "Swapped patterns ${a.label} and ${b.label}.")
    }

    fun togglePatternPreview(slot: PatternSlot) {
        if (_preview.value?.slot == slot) stopPatternPreview() else startPatternPreview(slot)
    }

    private fun startPatternPreview(slot: PatternSlot) {
        val pat = project.value.patterns[slot] ?: return
        val proj = project.value
        viewModelScope.launch {
            _busy.value = "Rendering pattern ${slot.label} …"
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    PatternRender.render(pat, { b, p ->
                        proj.pad(b, p)?.let { PadAudio.patternVoice(it, proj.bank(b).forceMono) }
                    })
                }.getOrNull()
            }
            _busy.value = null
            if (result == null) {
                message("Pattern ${slot.label} could not be rendered.", error = true); return@launch
            }
            c.player.play("pattern:${slot.label}", Audio(PatternRender.SR, listOf(result.left, result.right)), loop = true,
                loopStart = result.loopStart)
            _preview.value = PatternPreview(slot, result.stepSeconds, pat.length, result.frames)
            if (result.missing.isNotEmpty()) {
                message("Empty pads in this pattern: " + result.missing.joinToString(", ") { "${it.first}${it.second}" })
            }
        }
    }

    fun stopPatternPreview() {
        if (_preview.value != null) {
            _preview.value = null
            c.player.stop()
        }
    }

    // ------------------------------------------------------------ presets

    fun presetsRoot(): VNode {
        val uri = settings.value.presetsTreeUri
        if (uri != null) SafNode.fromTree(getApplication(), Uri.parse(uri))?.let { return it }
        return FileNode(c.internalPresets)
    }

    val presetsLocationLabel: String
        get() = if (settings.value.presetsTreeUri != null) "Your folder" else "Inside the app"

    fun setPresetsFolder(uri: Uri?) {
        val cr = getApplication<Application>().contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (uri != null) runCatching { cr.takePersistableUriPermission(uri, flags) }
        c.settings.update { it.copy(presetsTreeUri = uri?.toString()) }
    }

    suspend fun listPresets(): List<Pair<VNode, Presets.Manifest>> = withContext(Dispatchers.IO) {
        runCatching { Presets.list(presetsRoot()) }.getOrDefault(emptyList())
    }

    fun savePreset(name: String, banks: List<Char>, patternBanks: List<Int>, onDone: () -> Unit = {}) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            message("Give the preset a name.", error = true); return
        }
        work("Saving preset …") {
            val r = Presets.save(presetsRoot(), clean, project.value, banks, patternBanks)
            c.settings.addRecentPreset(clean)
            val problems = r.failures + r.problems
            if (problems.isNotEmpty()) showAlert("Preset saved, but:", problems.take(12).joinToString("\n"))
            else message("Preset “$clean” saved.")
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun loadPreset(dir: VNode, manifest: Presets.Manifest, banks: List<Char>, patternBanks: List<Int>, into: Char?, onDone: () -> Unit = {}) {
        c.player.stop()
        work("Loading preset …") {
            val r = Presets.load(dir, manifest, project.value, c.store, banks, patternBanks, into)
            repo.update { r.project }
            c.settings.addRecentPreset(dir.name)
            val problems = r.missing.map { "Missing: $it" } + r.patternProblems
            if (problems.isNotEmpty()) showAlert("Loaded with problems", problems.take(12).joinToString("\n"))
            else message("Preset “${dir.name}” loaded." + if (r.patternsLoaded > 0) " ${r.patternsLoaded} patterns." else "")
            (into ?: banks.firstOrNull())?.let { selectBank(it) }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun deletePreset(dir: VNode, onDone: () -> Unit) = work("Deleting preset …") {
        dir.deleteRecursively()
        message("Preset “${dir.name}” deleted.")
        withContext(Dispatchers.Main) { onDone() }
    }

    // ------------------------------------------------------------ waveform library

    private val _library = MutableStateFlow<Map<String, WaveEntry>>(emptyMap())
    /** The user's own waveforms (drawn or imported), by name. */
    val library: StateFlow<Map<String, WaveEntry>> = _library.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { _library.value = c.waveLibrary.load() }
    }

    private fun saveLibrary(m: Map<String, WaveEntry>) {
        _library.value = m
        viewModelScope.launch(Dispatchers.IO) { c.waveLibrary.save(m) }
    }

    /** A name not yet used by a built-in family or a library entry. */
    fun uniqueWaveName(base: String, allow: String? = null): String {
        val taken = _library.value.keys + io.github.pyp6.core.wavetable.Wavetable.catalogue.families.keys
        var name = base.trim().ifEmpty { "Custom" }.take(24)
        if (name == allow || name !in taken) return name
        var i = 2
        while ("$name $i" in taken) i++
        return "$name $i"
    }

    fun putWaveEntries(entries: List<WaveEntry>, replacing: String? = null) {
        val m = LinkedHashMap(_library.value)
        replacing?.let { m.remove(it) }
        for (e in entries) m[e.name] = e
        saveLibrary(m)
    }

    fun deleteWaveEntries(names: Collection<String>) = saveLibrary(_library.value.filterKeys { it !in names })

    /** Reads a .p6wf pack someone sent; only shapes whose names are new are added. */
    fun importWavePack(uri: Uri) = work("Reading waveform pack …") {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val (group, entries) = WaveLibrary.importPack(bytes)
        val fresh = entries.filter { it.name !in _library.value }
        putWaveEntries(fresh)
        message("${fresh.size} waveforms added to “$group”." + if (fresh.size < entries.size) " ${entries.size - fresh.size} already existed." else "")
    }

    /** Writes one folder of the library as a .p6wf pack. */
    fun exportWavePack(group: String, uri: Uri) = work("Writing waveform pack …") {
        val entries = _library.value.values.filter { (it.group ?: io.github.pyp6.core.wavetable.Wavetable.DEFAULT_USER_GROUP) == group }
        val bytes = WaveLibrary.exportPack(group, entries, c.appVersion)
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        message("Saved ${entries.size} waveforms as a pack.")
    }

    // ------------------------------------------------------------ settings

    fun setTheme(key: String) = c.settings.update { it.copy(theme = key) }

    fun updateSettings(block: (io.github.pyp6.app.data.Settings) -> io.github.pyp6.app.data.Settings) = c.settings.update(block)

    fun storageBytes(): Long = c.store.sizeBytes()

    fun clearUnusedFiles() = work("Cleaning up …") {
        val freed = c.store.prune(repo.referencedFiles())
        message("Freed ${formatBytes(freed)}.")
    }

    fun summaryFor(path: String) = WaveCache.get(path)

    override fun onCleared() {
        c.player.stop()
        super.onCleared()
    }
}

fun formatBytes(n: Long): String = when {
    n >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.2f MB", n / 1048576.0)
    n >= 1024 -> String.format(java.util.Locale.ROOT, "%.0f KB", n / 1024.0)
    else -> "$n B"
}

fun formatSeconds(s: Double): String = String.format(java.util.Locale.ROOT, "%.2f s", s)
