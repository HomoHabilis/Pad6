package io.github.pyp6.core

import io.github.pyp6.core.audio.Audio
import io.github.pyp6.core.audio.Wav
import io.github.pyp6.core.device.P6Drive
import io.github.pyp6.core.dsp.Chop
import io.github.pyp6.core.dsp.ChopNormalize
import io.github.pyp6.core.dsp.Edit
import io.github.pyp6.core.dsp.Fft
import io.github.pyp6.core.dsp.Resampler
import io.github.pyp6.core.io.FileNode
import io.github.pyp6.core.model.PadAudio
import io.github.pyp6.core.model.PadRef
import io.github.pyp6.core.model.PadState
import io.github.pyp6.core.model.Project
import io.github.pyp6.core.model.SampleStore
import io.github.pyp6.core.model.WavetableState
import io.github.pyp6.core.model.WtConfig
import io.github.pyp6.core.pattern.P6Pattern
import io.github.pyp6.core.pattern.PatternRender
import io.github.pyp6.core.pattern.PatternSlot
import io.github.pyp6.core.preset.Presets
import io.github.pyp6.core.prm.Prm
import io.github.pyp6.core.wavetable.WaveEntry
import io.github.pyp6.core.wavetable.WaveLibrary
import io.github.pyp6.core.wavetable.Wavetable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class CoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sine(rate: Int, seconds: Double, hz: Double = 440.0, channels: Int = 1) =
        Audio(rate, List(channels) { FloatArray((rate * seconds).toInt()) { i -> (0.5 * sin(2 * PI * hz * i / rate)).toFloat() } })

    @Test
    fun fftMatchesTheDefinitionForAnyLength() {
        for (n in listOf(1, 2, 7, 16, 674, 1000, 1024)) {
            val x = DoubleArray(n) { sin(it * 0.37) + 0.3 * cos(it * 1.9) }
            val (re, im) = Fft.rfft(x)
            for (k in re.indices step maxOf(1, re.size / 13)) {
                var r = 0.0; var i = 0.0
                for (t in 0 until n) { r += x[t] * cos(2 * PI * k * t / n); i -= x[t] * sin(2 * PI * k * t / n) }
                assertEquals("n=$n k=$k re", r, re[k], 1e-7 * n)
                assertEquals("n=$n k=$k im", i, im[k], 1e-7 * n)
            }
            val back = Fft.irfft(re, im, n)
            for (t in 0 until n) assertEquals(x[t], back[t], 1e-9)
        }
    }

    @Test
    fun wavRoundTripsAt16Bit() {
        val a = sine(22050, 0.2, channels = 2)
        val f = tmp.newFile("a.wav")
        Wav.writePcm16(a, f)
        val info = Wav.readInfo(f)
        assertEquals(22050, info.rate); assertEquals(2, info.channels); assertEquals(a.frames, info.frames)
        assertTrue(info.isPcm16)
        val b = Wav.read(f)
        for (i in 0 until a.frames) assertEquals(a.channels[1][i], b.channels[1][i], 1.0f / 16000)
    }

    @Test
    fun resamplingKeepsLengthAndPitch() {
        val a = sine(44100, 1.0, 441.0)
        val b = Resampler.resample(a, 11025)
        assertEquals(11025, b.frames)
        // Zero crossings of a 441 Hz tone: 882 per second at any rate.
        var crossings = 0
        val m = b.channels[0]
        for (i in 1 until m.size) if ((m[i - 1] < 0) != (m[i] < 0)) crossings++
        assertTrue("crossings $crossings", abs(crossings - 882) <= 2)
        // Vari-speed: an octave up halves the length.
        val up = Edit.pitchAndResample(a, 1200, 44100)
        assertEquals(22050, up.frames)
    }

    @Test
    fun trimSnapsToZeroCrossingsAndNormalizeHitsTarget() {
        val a = sine(44100, 1.0)
        val t = Edit.trim(a, 0.25, 0.75)
        assertTrue(abs(t.frames - 22050) < 44100 * 0.011)
        assertEquals(0.98f, Edit.normalize(a).peak(), 1e-4f)
    }

    @Test
    fun chopFillsTheWholeGridExactly() {
        val parts = listOf(sine(44100, 0.3), sine(22050, 0.05, 880.0), sine(48000, 2.0, 220.0, 2))
        val out = Chop.build(parts, 22050, 1, 8, ChopNormalize.PER_SAMPLE)
        assertEquals((11.8 * 22050).toInt(), out.frames)
        assertEquals(1, out.channelCount)
        // Slot 4 onwards is silence.
        val slot = out.frames / 8
        assertEquals(0f, out.slice(slot * 4, out.frames).peak(), 0f)
    }

    @Test
    fun durationRulesFollowTheDevice() {
        val f = tmp.newFile("long.wav")
        Wav.writePcm16(sine(44100, 7.0), f)
        val st = PadState(f.absolutePath, target_rate = 44100)
        assertNotNull(PadAudio.durationWarning(st, false))
        assertNull(PadAudio.durationWarning(st.copy(target_rate = 22050), false))
        val frac = PadAudio.truncateFraction(f, 44100, 0, false)!!
        assertEquals(5.9 / 7.0, frac, 1e-6)
        // Pitching down an octave doubles the length.
        assertNotNull(PadAudio.durationWarning(st.copy(target_rate = 22050, pitch_cents = -1200), false))
    }

    private fun prmText(bank: Char, pad: Int) = Prm.render(bank, pad, 0, 100, 1000)

    @Test
    fun exportWritesPadFoldersConvertedWithRetargetedPrm() {
        val store = SampleStore(tmp.newFolder("samples"))
        val drive = FileNode(tmp.newFolder("P6")).also { it.createDirectory("IMPORT") }
        val a = store.write(sine(48000, 0.5, channels = 2), "Kick.wav", "imp")
        store.attachPrm(a, prmText('A', 1).toByteArray())
        val b = store.write(sine(44100, 0.5), "Snare.wav", "imp")
        store.attachPrm(b, prmText('A', 1).toByteArray())
        var p = Project()
        p = p.withPad(PadRef('C', 2), PadState(a.absolutePath, target_rate = 22050, display_name = "Kick.wav"))
        p = p.withPad(PadRef('C', 5), PadState(b.absolutePath, target_rate = 44100, display_name = "Snare.wav"))
        val import = P6Drive.find(drive, P6Drive.IMPORT)!!
        // A stale file that must go.
        import.dir("BANK_C").dir("PAD_2").writeFile("old.wav", ByteArray(10))
        val r = P6Drive.exportBank(import, p, 'C', includePrm = true, tempDir = store.tempDir)
        assertEquals(2, r.copied); assertTrue(r.errors.isEmpty())
        val pad2 = File(drive.file, "IMPORT/BANK_C/PAD_2")
        val files2 = pad2.list()!!.sorted()
        assertEquals(1, files2.size)                          // rate changed: .PRM dropped
        val w = Wav.readInfo(File(pad2, files2[0]))
        assertEquals(22050, w.rate); assertTrue(w.isPcm16)
        val pad5 = File(drive.file, "IMPORT/BANK_C/PAD_5")
        val prm = pad5.listFiles()!!.first { it.name.endsWith(".PRM") }
        assertEquals(P6.phraseNumber('C', 5), Prm.readValues(prm.readText())["PHRASE"])
        assertEquals(3, P6Drive.importFiles(import).size)       // two samples and C5's .PRM
        assertEquals(3 to emptyList<String>(), P6Drive.wipeImport(import))
        assertEquals(0, pad5.list()!!.size)
    }

    @Test
    fun exportFolderIsFoundFromTheDriveRoot() {
        val drive = FileNode(tmp.newFolder("P6drive"))
        val bankB = drive.dir("P-6").dir("EXPORT").dir("BANK_B")
        Wav.writePcm16(sine(44100, 0.1), File(tmp.root, "P6drive/P-6/EXPORT/BANK_B/PAD_3/x.WAV").also { it.parentFile.mkdirs() })
        bankB.dir("PAD_3").writeFile("x.PRM", prmText('B', 3).toByteArray())
        val found = P6Drive.resolveExportFolder(drive, 'A')!!
        assertEquals("BANK_B", found.folder.name)
        val f = P6Drive.findPadFile(found.folder, 3)!!
        assertEquals("x.WAV", f.name)
        assertNotNull(P6Drive.prmBeside(found.folder.child("PAD_3")!!, f))
        assertNull(P6Drive.findPadFile(found.folder, 1))
    }

    @Test
    fun patternsSaveRemovesStaleSlotsAndLoadsBack() {
        val dir = FileNode(tmp.newFolder("RESTORE"))
        dir.writeFile("P6_PTN4-16.PRM", "x".toByteArray())
        val pats = sortedMapOf(PatternSlot(1, 1) to P6Pattern.blank(), PatternSlot(2, 3) to P6Pattern.blank())
        assertTrue(P6Drive.savePatterns(dir, pats).isEmpty())
        assertEquals(listOf("P6_PTN1-01.PRM", "P6_PTN2-03.PRM"), dir.children().map { it.name })
        val (from, files) = P6Drive.findPatternFiles(FileNode(tmp.root))
        assertEquals("RESTORE", from.name)
        assertEquals(2, files.size)
        val blank = P6Pattern.blank()
        assertTrue(blank.isEmpty)
        assertEquals(32, blank.length)
    }

    @Test
    fun patternPreviewRendersALoop() {
        val p = P6Pattern.fromBytes(CoreTest::class.java.getResourceAsStream("/golden/pattern_a.prm")!!.readBytes())
        val tone = sine(44100, 0.5)
        val res = PatternRender.render(p, { b, pad ->
            if (b == 'A') PatternRender.PadVoice(tone.channels[0], tone.channels[0], 44100.0, null) else null
        })
        val expectFrames = Math.rint(16 * PatternRender.stepSeconds(p) * 44100).toInt()
        assertEquals(expectFrames, res.frames)
        assertTrue(res.notesPlayed > 0)
        assertTrue(res.missing.isNotEmpty())
        assertTrue(res.left.any { it != 0f })
    }

    @Test
    fun patternPreviewLoopsTheSliceOfAChoppedLoopingPad() {
        // A 16-slice pad with LOOP on, held briefly with a long release: the
        // P-6 keeps repeating the slice while the release fades it.
        val tone = sine(22050, 4.0)
        val prm = mapOf("LOOP" to 1, "GATE" to 1, "CHOP" to 16, "TENV_SUSTAIN" to 69, "TENV_RELEASE" to 252)
        val slice = 4.0 / 16
        fun peakAfter(v: PatternRender.PadVoice, at: Double): Float {
            val (l, _) = v.render(60, 100, 0.15, 1.7) ?: return 0f
            return l.drop((at * 44100).toInt()).maxOfOrNull { abs(it) } ?: 0f
        }
        val looping = PatternRender.PadVoice(tone.channels[0], tone.channels[0], 22050.0, prm)
        assertTrue(peakAfter(looping, slice * 4) > 0.1f)
        // LOOP off: the slice plays once.
        val once = PatternRender.PadVoice(tone.channels[0], tone.channels[0], 22050.0, prm + ("LOOP" to 0))
        assertEquals(0f, peakAfter(once, slice + 0.01))
        // No .PRM: the whole sample once, not held until the next note.
        val plain = PatternRender.PadVoice(tone.channels[0], tone.channels[0], 22050.0, null)
        assertEquals(4.0, plain.render(60, 100, 0.15, 20.0)!!.first.size / 44100.0, 0.01)
    }

    @Test
    fun patternPreviewFirstPassHasNoTailsFromTheEnd() {
        // One note on step 9 with a tail that rings past the pattern's end.
        val text = P6Pattern.blankText.split("\n").joinToString("\n") { line ->
            if (line.startsWith("STEP_NOTE_SMPL 9\t")) line.replace("PART1=-1 NOTE1=-1 VELO1=0 LENG1=0", "PART1=0 NOTE1=60 VELO1=100 LENG1=100")
            else line
        }
        val p = P6Pattern(text)
        val tone = sine(22050, 4.0)
        val prm = mapOf("LOOP" to 1, "GATE" to 1, "CHOP" to 16, "TENV_RELEASE" to 252)
        val res = PatternRender.render(p, { b, pad ->
            if (b == 'A' && pad == 1) PatternRender.PadVoice(tone.channels[0], tone.channels[0], 22050.0, prm) else null
        })
        val hit = (8 * PatternRender.stepSeconds(p) * PatternRender.SR).toInt()
        fun peak(from: Int, to: Int) = (from until to).maxOf { abs(res.left[it]) }
        assertEquals(res.frames, res.loopStart)
        assertEquals(2 * res.frames, res.left.size)
        assertEquals(0f, peak(0, hit - 100))                                   // first pass: silent until the note
        assertTrue(peak(res.loopStart, res.loopStart + hit - 100) > 0.05f)     // looping: the tail rings on
        assertTrue(peak(hit, hit + 1000) > 0.05f)
    }

    @Test
    fun wipeImportTakesThePrmFilesAlong() {
        val import = tmp.newFolder("IMPORT")
        val pad = File(import, "BANK_A/PAD_1").apply { mkdirs() }
        val stray = File(import, "BANK_C/PAD_4").apply { mkdirs() }
        listOf("Kick.WAV", "Kick.PRM", "._Kick.WAV", "notes.txt").forEach { File(pad, it).writeText("x") }
        File(stray, "Old.PRM").writeText("x")        // a .PRM whose sample is already gone
        val (n, errors) = P6Drive.wipeImport(FileNode(import))
        assertEquals(4, n)
        assertTrue(errors.isEmpty())
        assertEquals(listOf("notes.txt"), pad.list()!!.toList())
        assertEquals(0, stray.list()!!.size)
    }

    @Test
    fun presetsRoundTripIncludingWavetablesAndPatterns() {
        val store = SampleStore(tmp.newFolder("s"))
        val a = store.write(sine(44100, 0.2), "Kick.wav", "imp")
        store.attachPrm(a, prmText('A', 1).toByteArray())
        val wt = Wavetable.build(listOf(Wavetable.Item.Builtin("Saw"), Wavetable.Item.Builtin("Sine")), 36, 1, 0)
        val wtFile = store.newFile("wavetable", "wt", ".WAV")
        Wav.writePcm16Mono(wt.pcm, 44100, wtFile)
        store.attachPrm(wtFile, Prm.render('B', 1, 0, wt.meta.L, wt.meta.total_frames).toByteArray())
        var p = Project()
        p = p.withPad(PadRef('A', 1), PadState(a.absolutePath, target_rate = 22050, pitch_cents = 300, mono = true, display_name = "Kick"))
        p = p.withPad(PadRef('B', 1), PadState(wtFile.absolutePath, display_name = "Synth Mode",
            wavetable = WavetableState(WtConfig(families = listOf("Saw", "Sine")), wt.meta), wt_patch = "Acid", wt_poly = true))
        p = p.withBank('B', p.bank('B').copy(forceMono = true))
        p = p.copy(patterns = mapOf(PatternSlot(1, 2) to P6Pattern.blank(), PatternSlot(3, 1) to P6Pattern.blank()))
        val parent = FileNode(tmp.newFolder("presets"))
        val saved = Presets.save(parent, "My Set", p, listOf('A', 'B'), listOf(1))
        assertTrue(saved.failures.toString(), saved.failures.isEmpty())
        assertTrue(saved.problems.toString(), saved.problems.isEmpty())
        val m = Presets.readManifest(saved.folder)!!
        assertEquals(listOf('A', 'B'), m.bankLetters)
        assertEquals(listOf(PatternSlot(1, 2)), m.patternSlots)

        val store2 = SampleStore(tmp.newFolder("s2"))
        val loaded = Presets.load(saved.folder, m, Project(), store2, listOf('A', 'B'), listOf(1), null)
        assertTrue(loaded.missing.isEmpty())
        val pa = loaded.project.pad('A', 1)!!
        assertEquals(22050, pa.target_rate); assertEquals(300, pa.pitch_cents); assertTrue(pa.mono)
        assertTrue(File(pa.filepath).path.startsWith(store2.root.path))
        assertNotNull(PadAudio.prmFor(File(pa.filepath)))
        val pb = loaded.project.pad('B', 1)!!
        assertEquals(wt.meta, pb.wavetable!!.meta)
        assertEquals("Acid", pb.wt_patch)
        assertTrue(loaded.project.bank('B').forceMono)
        assertEquals(1, loaded.patternsLoaded)

        // Saving only bank A over it keeps bank B.
        val again = Presets.save(parent, "My Set", p, listOf('A'), emptyList())
        assertEquals(listOf('A', 'B'), Presets.readManifest(again.folder)!!.bankLetters)
        // Into a different bank on load.
        val into = Presets.load(saved.folder, m, Project(), store2, listOf('A'), emptyList(), 'H')
        assertNotNull(into.project.pad('H', 1)); assertNull(into.project.pad('A', 1))
    }

    @Test
    fun padSwapsRewritePatternsWhenSyncIsOn() {
        val text = String(CoreTest::class.java.getResourceAsStream("/golden/pattern_a.prm")!!.readBytes(), Charsets.ISO_8859_1)
        val p = Project(patterns = mapOf(PatternSlot(1, 1) to P6Pattern(text)))
        val (off, n0) = p.swapPads(PadRef('A', 1), PadRef('B', 1), sync = false)
        assertEquals(0, n0); assertFalse(off.patternsDirty)
        val (on, n1) = p.swapPads(PadRef('A', 1), PadRef('B', 1), sync = true)
        assertEquals(1, n1)
        val used = on.patterns[PatternSlot(1, 1)]!!.usedPads()
        assertTrue(('B' to 1) in used && ('A' to 1) in used)
        val (banks, n2) = p.swapBanks('A', 'H', sync = true)
        assertEquals(1, n2)
        assertTrue(('H' to 1) in banks.patterns[PatternSlot(1, 1)]!!.usedPads())
    }

    @Test
    fun waveformPacksRoundTrip() {
        val a = List(512) { sin(2 * PI * it / 512) }
        val b = List(512) { 2.0 * it / 512 - 1 }
        val bytes = WaveLibrary.exportPack("Mine", listOf(WaveEntry("draw", "One", "Mine", a, b)))
        val (group, entries) = WaveLibrary.importPack(bytes)
        assertEquals("Mine", group)
        assertEquals(1, entries.size)
        assertEquals(a[100], entries[0].a!![100], 1e-4)
        val lib = WaveLibrary(File(tmp.root, "lib.json"))
        lib.save(mapOf("One" to entries[0]))
        assertEquals("One", lib.load().keys.single())
    }

    @Test
    fun cycleImportDetectsWholeWavetables() {
        val frames = 8
        val L = 2048
        val data = DoubleArray(frames * L) { i -> val k = i / L; sin(2 * PI * (i % L) / L) * (1 + k) / frames + sin(4 * PI * (i % L) / L) * 0.1 }
        assertEquals(L, Wavetable.detectFrameSize(data))
        val (shapes, size) = Wavetable.splitTable(data, null)
        assertEquals(L, size); assertEquals(frames, shapes.size); assertEquals(512, shapes[0].size)
        val entries = shapes.mapIndexed { i, s -> WaveEntry("draw", "T$i", a = s, b = s) }
        assertEquals(7, Wavetable.chainFamilies(entries).size)
        assertEquals(4, Wavetable.pairFamilies(entries).size)
        assertEquals(8, Wavetable.multiFamilies(entries).single().shapes!!.size)
        // A custom family builds like a built-in one.
        val r = Wavetable.build(Wavetable.chainFamilies(entries).map { Wavetable.Item.Custom(it) }, 48, 2, 12)
        assertEquals(255, r.meta.counts.sum())
    }
}
