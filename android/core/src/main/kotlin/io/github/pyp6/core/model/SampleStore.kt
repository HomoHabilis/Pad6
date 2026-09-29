package io.github.pyp6.core.model

import io.github.pyp6.core.P6
import io.github.pyp6.core.audio.Audio
import io.github.pyp6.core.audio.Wav
import io.github.pyp6.core.io.SafeName
import java.io.File

/**
 * The folder where every sample a pad uses lives: imports, edits, chops,
 * wavetables. The desktop app's ~/.pyp6/temp and ~/.pyp6/wavetables in one
 * place. Files are never edited in place - every change is a new file - so
 * an undo step can always point back at the old one.
 */
class SampleStore(val root: File) {
    init {
        root.mkdirs()
    }

    val tempDir: File get() = File(root, ".export").also { it.mkdirs() }

    fun newFile(source: String?, tag: String, ext: String = ".wav"): File = File(root, SafeName.derived(source, tag, ext))

    /** Writes [audio] as 16-bit PCM under a name derived from [source]. */
    fun write(audio: Audio, source: String?, tag: String): File {
        val f = newFile(source, tag)
        Wav.writePcm16(audio, f)
        return f
    }

    /** Copies [prm] next to [sample] under the sample's base name. */
    fun attachPrm(sample: File, prmText: ByteArray) {
        File(sample.path.substringBeforeLast('.') + ".PRM").writeBytes(prmText)
    }

    /**
     * A pad state for a freshly loaded sample: rate detected (lowest offered
     * rate that holds everything the file has), pitch 0, not mono.
     */
    fun newPadState(file: File, displayName: String?, fromSync: Boolean = false): PadState {
        val rate = runCatching { Wav.readInfo(file).rate }.getOrDefault(44100)
        return PadState(
            filepath = file.absolutePath,
            target_rate = P6.defaultRateFor(rate),
            display_name = displayName,
            from_sync = fromSync,
        )
    }

    /** A copied wavetable pad gets its own file, so the two never share one .PRM. */
    fun duplicateForCopy(state: PadState): PadState {
        if (!state.isWavetable) return state
        val src = File(state.filepath)
        if (!src.isFile) return state
        val dst = newFile("wavetable", "wt", ".WAV")
        src.copyTo(dst, overwrite = true)
        PadAudio.prmFor(src)?.copyTo(File(dst.path.substringBeforeLast('.') + ".PRM"), overwrite = true)
        return state.copy(filepath = dst.absolutePath)
    }

    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Deletes every file no project in [keep] refers to (the current state
     * plus the undo history), along with each one's .PRM. Returns bytes freed.
     */
    fun prune(keep: Set<String>): Long {
        var freed = 0L
        val keepBases = keep.map { it.substringBeforeLast('.') }.toSet()
        root.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val base = f.path.substringBeforeLast('.')
            if (f.absolutePath in keep || base in keepBases) return@forEach
            freed += f.length()
            f.delete()
        }
        tempDir.listFiles()?.forEach { freed += it.length(); it.delete() }
        return freed
    }
}
