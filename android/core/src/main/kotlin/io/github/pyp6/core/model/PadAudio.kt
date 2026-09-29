package io.github.pyp6.core.model

import io.github.pyp6.core.P6
import io.github.pyp6.core.audio.Audio
import io.github.pyp6.core.audio.Wav
import io.github.pyp6.core.audio.WavInfo
import io.github.pyp6.core.dsp.Edit
import io.github.pyp6.core.io.SafeName
import io.github.pyp6.core.pattern.PatternRender
import io.github.pyp6.core.prm.Prm
import java.io.File
import java.util.Locale

/**
 * What a pad sounds like on the device, and what goes to the device.
 * The single place the rate/pitch/mono rules live, as in the desktop app:
 * export, playback, warnings, the storage estimate and the .PRM badge all
 * ask here so they cannot disagree.
 */
object PadAudio {

    fun info(file: File): WavInfo? = runCatching { Wav.readInfo(file) }.getOrNull()

    /** The .PRM next to [sample] (same base name), if there is one. */
    fun prmFor(sample: File): File? {
        val base = sample.path.substringBeforeLast('.', sample.path)
        return listOf("$base.PRM", "$base.prm").map { File(it) }.firstOrNull { it.isFile }
    }

    /**
     * Whether a .PRM still describes what export produces. A rate change or
     * pitch shift changes the frame count its START_POS/SIZE/LOOP_SIZE point
     * into, so it retires the sidecar; mono and 16-bit do not.
     */
    fun prmSurvives(file: File, targetRate: Int, pitchCents: Int): Boolean {
        if (pitchCents != 0) return false
        val i = info(file) ?: return true
        return i.rate == targetRate
    }

    /** What export does with the pad's .PRM: nothing to send, sends it, or drops it. */
    enum class PrmStatus { NONE, KEPT, DROPPED }

    fun prmStatus(state: PadState): PrmStatus {
        if (state.isWavetable) return PrmStatus.KEPT
        val f = File(state.filepath)
        if (prmFor(f) == null) return PrmStatus.NONE
        return if (prmSurvives(f, state.target_rate, state.pitch_cents)) PrmStatus.KEPT else PrmStatus.DROPPED
    }

    fun channelsAfter(info: WavInfo, forceMono: Boolean) = if (forceMono || info.channels == 1) 1 else 2

    /** Length as the device plays it (pitch is vari-speed), in seconds. */
    fun heardSeconds(info: WavInfo, pitchCents: Int): Double =
        info.durationSeconds / Edit.pitchSpeedFactor(pitchCents)

    /** The "too long for this rate" warning, or null. */
    fun durationWarning(state: PadState, forceMono: Boolean): String? {
        if (state.isWavetable) return null
        val i = info(File(state.filepath)) ?: return null
        val duration = heardSeconds(i, state.pitch_cents)
        val ch = channelsAfter(i, forceMono)
        val limit = P6.maxSeconds(state.target_rate, ch) ?: return null
        if (duration <= limit) return null
        val pitchNote = if (state.pitch_cents != 0) String.format(Locale.ROOT, " (with pitch %+dc)", state.pitch_cents) else ""
        return String.format(Locale.ROOT, "Sample is %.1fs long%s, but at %dHz/%s only %ss are possible.",
            duration, pitchNote, state.target_rate, if (ch == 1) "Mono" else "Stereo", fmtLimit(limit))
    }

    private fun fmtLimit(v: Double): String = if (v == Math.floor(v)) v.toInt().toString() else v.toString()

    /**
     * Where the device's length limit falls inside the file, as a fraction
     * of the file's own timeline, or null when it fits.
     */
    fun truncateFraction(file: File, targetRate: Int, pitchCents: Int, forceMono: Boolean): Double? {
        val i = info(file) ?: return null
        if (i.durationSeconds <= 0) return null
        val limit = P6.maxSeconds(targetRate, channelsAfter(i, forceMono)) ?: return null
        val limitOriginal = limit * Edit.pitchSpeedFactor(pitchCents)
        if (limitOriginal >= i.durationSeconds) return null
        return limitOriginal / i.durationSeconds
    }

    /** Seconds the pad may hold at its settings, measured on the file's own timeline. */
    fun fitSeconds(file: File, targetRate: Int, pitchCents: Int, forceMono: Boolean): Double? {
        val i = info(file) ?: return null
        val frac = truncateFraction(file, targetRate, pitchCents, forceMono) ?: return null
        return i.durationSeconds * frac
    }

    fun needsConversion(i: WavInfo, targetRate: Int, pitchCents: Int, forceMono: Boolean): Boolean =
        targetRate != i.rate || pitchCents != 0 || (forceMono && i.channels > 1) || !i.isPcm16

    /** Size on the device after conversion, as the storage bar counts it. */
    fun estimatedExportBytes(state: PadState, forceMono: Boolean): Long {
        val f = File(state.filepath)
        if (state.isWavetable) return f.length()
        val i = info(f) ?: return f.length()
        if (!needsConversion(i, state.target_rate, state.pitch_cents, forceMono)) return f.length()
        val duration = heardSeconds(i, state.pitch_cents)
        val ch = if (forceMono && i.channels > 1) 1 else i.channels
        return (duration * state.target_rate * ch * 2).toLong() + 44
    }

    fun bankBytes(bank: BankState): Long =
        bank.pads.filterNotNull().sumOf { estimatedExportBytes(it, bank.forceMono || it.mono) }

    /** The pad's audio rendered exactly as export writes it (pitch, mono, rate, 16-bit). */
    fun renderForDevice(state: PadState, forceMono: Boolean): Audio {
        var a = Wav.read(File(state.filepath))
        if (state.isWavetable) return a
        if (forceMono && a.channelCount > 1) a = a.toMono()
        return Edit.pitchAndResample(a, state.pitch_cents, state.target_rate)
    }

    /**
     * The file export copies to the device: the pad's own file when nothing
     * needs converting (or for a wavetable, byte for byte), otherwise a
     * converted copy written into [tempDir].
     */
    fun exportFile(state: PadState, forceMono: Boolean, tempDir: File): File {
        val src = File(state.filepath)
        if (state.isWavetable) return src
        val i = info(src) ?: return src
        if (!needsConversion(i, state.target_rate, state.pitch_cents, forceMono)) return src
        val audio = renderForDevice(state, forceMono)
        val suffix = buildString {
            append("_${state.target_rate}Hz")
            if (state.pitch_cents != 0) append(String.format(Locale.ROOT, "_%+dc", state.pitch_cents))
            if (forceMono && i.channels > 1) append("_mono")
            if (!i.isPcm16) append("_16bit")
        }
        val out = File(tempDir, SafeName.baseName(state.name.ifBlank { src.name }) + suffix + ".wav")
        Wav.writePcm16(audio, out)
        return out
    }

    /**
     * The .PRM text export writes beside the sample, or null for none: a
     * wavetable's is rendered fresh; a device sidecar goes along (PHRASE
     * retargeted to [bank]/[pad]) only while it still describes the file.
     */
    fun prmTextForExport(state: PadState, bank: Char, pad: Int, includePrm: Boolean): String? {
        state.wavetable?.let { wt ->
            val tpl = if (state.wt_patch in Prm.TEMPLATES) state.wt_patch else "Init"
            return Prm.render(bank, pad, 0, wt.meta.L, wt.meta.total_frames, tpl, state.wt_poly)
        }
        if (!includePrm) return null
        val src = File(state.filepath)
        if (!prmSurvives(src, state.target_rate, state.pitch_cents)) return null
        val prm = prmFor(src) ?: return null
        val text = String(prm.readBytes(), Charsets.ISO_8859_1)
        return Prm.retargetPhrase(text, bank, pad) ?: text
    }

    /** The pattern preview's voice for a pad: pitch as vari-speed, mono, and the .PRM while it survives. */
    fun patternVoice(state: PadState, forceMono: Boolean): PatternRender.PadVoice? {
        val f = File(state.filepath)
        val a = runCatching { Wav.read(f) }.getOrNull() ?: return null
        if (a.frames == 0) return null
        val src = if (forceMono || state.mono) a.toMono() else a
        val st = src.toStereo()
        var fs = src.rate.toDouble()
        val prm = if (!state.isWavetable && !prmSurvives(f, state.target_rate, state.pitch_cents)) null
        else prmFor(f)?.let { Prm.readValues(String(it.readBytes(), Charsets.ISO_8859_1)) }?.takeIf { it.isNotEmpty() }
        if (state.pitch_cents != 0 && !state.isWavetable) fs *= Edit.pitchSpeedFactor(state.pitch_cents)
        return PatternRender.PadVoice(st.channels[0], st.channels[1], fs, prm)
    }

    /** Playback audio for a pad as it will sound after export. Wavetables play each segment twice. */
    fun previewAudio(state: PadState, forceMono: Boolean): Audio {
        if (state.isWavetable) {
            val a = Wav.read(File(state.filepath))
            val L = state.wavetable!!.meta.L
            if (L <= 0 || a.frames < L * 255) return a
            val mono = a.channels[0]
            val out = FloatArray(L * 255 * io.github.pyp6.core.wavetable.Wavetable.AUDITION_REPEATS)
            var at = 0
            for (seg in 0 until 255) repeat(io.github.pyp6.core.wavetable.Wavetable.AUDITION_REPEATS) {
                mono.copyInto(out, at, seg * L, seg * L + L); at += L
            }
            return Audio(a.rate, listOf(out))
        }
        return renderForDevice(state, forceMono)
    }
}
