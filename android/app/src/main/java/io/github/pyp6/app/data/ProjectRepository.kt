package io.github.pyp6.app.data

import io.github.pyp6.core.P6
import io.github.pyp6.core.model.BankState
import io.github.pyp6.core.model.Project
import io.github.pyp6.core.pattern.P6Pattern
import io.github.pyp6.core.pattern.PatternSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The project the user is working on, with undo/redo (25 steps, like the
 * desktop app) and an autosave, so nothing is lost when Android closes the
 * app in the background.
 */
class ProjectRepository(private val file: File, scope: CoroutineScope) {
    private val patternDir = File(file.parentFile, "patterns")
    private val _project = MutableStateFlow(load())
    val project: StateFlow<Project> = _project.asStateFlow()
    val value: Project get() = _project.value

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private var savedPatterns: Map<PatternSlot, P6Pattern> = _project.value.patterns

    init {
        @OptIn(FlowPreview::class)
        scope.launch(Dispatchers.IO) {
            _project.drop(1).debounce(400).collect { save(it) }
        }
    }

    /** Applies a change as one undo step. */
    @Synchronized
    fun update(block: (Project) -> Project): Project {
        val before = _project.value
        val after = block(before)
        if (after == before) return after
        undoStack.addLast(before)
        while (undoStack.size > P6.MAX_UNDO_STEPS) undoStack.removeFirst()
        redoStack.clear()
        _project.value = after
        refreshFlags()
        return after
    }

    /** A change that is not worth an undo step (e.g. marking patterns saved). */
    @Synchronized
    fun replaceSilently(block: (Project) -> Project) {
        _project.value = block(_project.value)
    }

    @Synchronized
    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(_project.value)
        _project.value = prev
        refreshFlags()
        return true
    }

    @Synchronized
    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(_project.value)
        _project.value = next
        refreshFlags()
        return true
    }

    private fun refreshFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    /** Every sample file the project or its undo history still points at. */
    @Synchronized
    fun referencedFiles(): Set<String> =
        (undoStack + redoStack + listOf(_project.value)).flatMap { it.referencedFiles() }.toSet()

    @Serializable
    private data class Stored(
        val banks: Map<String, BankState> = emptyMap(),
        val patternSource: String? = null,
        val patternsDirty: Boolean = false,
    )

    private fun load(): Project {
        val stored = runCatching { Project.json.decodeFromString(Stored.serializer(), file.readText()) }.getOrNull()
            ?: return Project()
        val banks = P6.BANKS.associateWith { b ->
            val bs = stored.banks[b.toString()] ?: BankState()
            // Pads whose file went missing (cleared app storage) come back empty.
            bs.copy(pads = List(P6.PADS.size) { i -> bs.pads.getOrNull(i)?.takeIf { File(it.filepath).isFile } })
        }
        val patterns = sortedMapOf<PatternSlot, P6Pattern>()
        patternDir.listFiles()?.forEach { f ->
            val slot = PatternSlot.fromFileName(f.name) ?: return@forEach
            runCatching { patterns[slot] = P6Pattern.fromBytes(f.readBytes()) }
        }
        return Project(banks, patterns, stored.patternSource, stored.patternsDirty)
    }

    @Synchronized
    private fun save(p: Project) {
        runCatching {
            val stored = Stored(p.banks.mapKeys { it.key.toString() }, p.patternSource, p.patternsDirty)
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Project.json.encodeToString(Stored.serializer(), stored))
            tmp.renameTo(file)
            if (p.patterns != savedPatterns) {
                patternDir.mkdirs()
                for (slot in PatternSlot.ALL) {
                    val pat = p.patterns[slot]
                    val f = File(patternDir, slot.fileName)
                    if (pat == null) f.delete()
                    else if (savedPatterns[slot] !== pat) f.writeBytes(pat.toBytes())
                }
                savedPatterns = p.patterns
            }
        }
    }
}
