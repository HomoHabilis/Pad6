package io.github.pyp6.core

import io.github.pyp6.core.audio.Wav
import io.github.pyp6.core.dsp.Analysis
import io.github.pyp6.core.dsp.Stretch
import io.github.pyp6.core.io.SafeName
import io.github.pyp6.core.pattern.P6Pattern
import io.github.pyp6.core.pattern.PatternRender
import io.github.pyp6.core.prm.Prm
import io.github.pyp6.core.wavetable.MiniFreak
import io.github.pyp6.core.wavetable.Wavetable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The Kotlin port against the desktop app itself: tools/make_golden.py ran
 * the Python functions on these inputs and recorded what they returned.
 */
class GoldenTest {
    private fun res(name: String) = GoldenTest::class.java.getResourceAsStream("/golden/$name")!!.use { it.readBytes() }
    private val golden: JsonObject by lazy { Json.parseToJsonElement(String(res("golden.json"))).jsonObject }
    private fun wav(name: String) = Wav.read(res(name).inputStream())
    private fun ints(a: JsonArray) = a.map { it.jsonPrimitive.int }

    private fun assertCutsClose(expected: List<Int>, actual: List<Int>, tol: Int, what: String) {
        assertEquals("$what count (expected $expected, got $actual)", expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue("$what[$i]: expected ${expected[i]}, got ${actual[i]}", abs(expected[i] - actual[i]) <= tol)
        }
    }

    @Test
    fun tempoHitsAndOnsetsMatchTheDesktopApp() {
        for (name in listOf("loop120", "loop140", "loop90", "loop174")) {
            val g = golden[name]!!.jsonObject
            val audio = wav("$name.wav")
            val mono = audio.mono()
            val bpm = Analysis.detectBpm(mono, audio.rate)
            assertEquals("$name bpm", g["bpm"]!!.jsonPrimitive.double, bpm!!, 1e-9)
            val octave = g["octave"]!!
            val alt = Analysis.bpmOctaveAlternative(mono, audio.rate, bpm)
            if (octave is JsonNull) assertNull(alt) else assertEquals("$name octave", octave.jsonPrimitive.double, alt!!, 1e-9)
            assertCutsClose(ints(g["hits"]!!.jsonArray), Analysis.detectHits(mono, audio.rate, 1.0), 2, "$name hits")
            assertCutsClose(ints(g["hits18"]!!.jsonArray), Analysis.detectHits(mono, audio.rate, 1.8), 2, "$name hits18")
            assertCutsClose(ints(g["onsets"]!!.jsonArray), Analysis.detectOnsets(mono, audio.rate, 1.8), 0, "$name onsets")
        }
        val tone = wav("tone.wav")
        assertNull(Analysis.detectBpm(tone.mono(), tone.rate))
    }

    private fun floats(name: String): FloatArray {
        val b = ByteBuffer.wrap(res(name)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()) { b.get(it) }
    }

    private fun rmsDiff(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        var s = 0.0
        for (i in a.indices) s += (a[i] - b[i]).toDouble().let { it * it }
        return sqrt(s / a.size)
    }

    @Test
    fun timeStretchMatchesTheDesktopApp() {
        val audio = wav("loop120.wav")
        val mono = audio.mono()
        val onsets = Analysis.detectOnsets(mono, audio.rate, 1.8)
        val voc = Stretch.vocoder(audio, 0.8, onsets).channels[0]
        assertTrue("vocoder differs", rmsDiff(floats("stretch_vocoder_080.f32"), voc) < 1e-4)
        val hits = Analysis.detectHits(mono, audio.rate, 1.8)
        val sl = Stretch.slices(audio, 0.8, hits).channels[0]
        assertTrue("slices differ", rmsDiff(floats("stretch_slices_080.f32"), sl) < 1e-4)
    }

    @Test
    fun wavetablesMatchTheDesktopApp() {
        val cases = golden["wavetables"]!!.jsonObject
        for ((name, c0) in cases) {
            val c = c0.jsonObject
            val fams = c["families"]!!.jsonArray.map { Wavetable.Item.Builtin(it.jsonPrimitive.content) }
            val r = Wavetable.build(fams, c["midi"]!!.jsonPrimitive.int, c["cycles"]!!.jsonPrimitive.int, c["up"]!!.jsonPrimitive.int)
            val meta = c["meta"]!!.jsonObject
            assertEquals("$name L", meta["L"]!!.jsonPrimitive.int, r.meta.L)
            assertEquals("$name h", meta["h"]!!.jsonPrimitive.int, r.meta.h)
            assertEquals("$name total", meta["total_frames"]!!.jsonPrimitive.int, r.meta.total_frames)
            assertEquals("$name counts", ints(meta["counts"]!!.jsonArray), r.meta.counts)
            val want = ByteBuffer.wrap(res("wt_$name.i16")).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            assertEquals("$name length", want.remaining(), r.pcm.size)
            var worst = 0
            var worstAt = -1
            for (i in r.pcm.indices) {
                val d = abs(want.get(i) - r.pcm[i])
                if (d > worst) { worst = d; worstAt = i }
            }
            assertTrue("$name: largest difference $worst LSB at frame $worstAt (segment ${worstAt / r.meta.L})", worst <= 2)
        }
    }

    @Test
    fun miniFreakExportMatchesTheDesktopApp() {
        val cases = golden["minifreak"]!!.jsonObject
        for ((name, c0) in cases) {
            val c = c0.jsonObject
            val fams = c["families"]!!.jsonArray.map { Wavetable.Item.Builtin(it.jsonPrimitive.content) }
            val frames = c["frames"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.int }
            val note = c["note"]!!.jsonPrimitive.content
            val table = MiniFreak.build(fams, frames, note)
            assertEquals("$name frames", c["count"]!!.jsonPrimitive.int, table.frames.size)
            assertEquals("$name map", String(res("mf_$name.txt")), MiniFreak.map(table, note))

            val want = res("mf_$name.wav")
            val got = MiniFreak.wav(table)
            assertEquals("$name size", want.size, got.size)
            // Everything before the samples - RIFF, fmt, clm and data headers - byte for byte.
            val head = want.size - table.frames.size * MiniFreak.FRAME * 3
            for (i in 0 until head) assertEquals("$name header byte $i", want[i], got[i])
            var worst = 0
            for (i in head until want.size step 3) {
                fun s24(b: ByteArray) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i + 2].toInt() shl 16)
                worst = maxOf(worst, abs(s24(want) - s24(got)))
            }
            // 24-bit LSBs: 16 of them is still below one LSB of 16-bit audio.
            assertTrue("$name: largest difference $worst LSB (24-bit)", worst <= 16)
        }
    }

    @Test
    fun patternsMatchTheDesktopApp() {
        val g = golden["pattern"]!!.jsonObject
        val p = P6Pattern.fromBytes(res("pattern_a.prm"))
        assertEquals(g["length"]!!.jsonPrimitive.int, p.length)
        assertEquals(g["tempo"]!!.jsonPrimitive.double, p.tempo, 1e-9)
        assertEquals(g["note_count"]!!.jsonPrimitive.int, p.noteCount)
        assertEquals(g["used_pads"]!!.jsonArray.map { it.jsonPrimitive.content }, p.usedPads().map { "${it.first}${it.second}" }.sorted())
        val notes = PatternRender.stepNotes(p)
        val want = g["notes"]!!.jsonArray
        assertEquals(want.size, notes.size)
        for ((w0, n) in want.zip(notes)) {
            val w = w0.jsonObject
            assertEquals(w["step"]!!.jsonPrimitive.int, n.step)
            assertEquals(w["part"]!!.jsonPrimitive.int, n.part)
            assertEquals(w["note"]!!.jsonPrimitive.int, n.note)
            assertEquals(w["velo"]!!.jsonPrimitive.int, n.velo)
            assertEquals(w["sub"]!!.jsonPrimitive.int, n.sub)
            assertEquals(w["mt"]!!.jsonPrimitive.int, n.mt)
            assertEquals(w["steps"]!!.jsonPrimitive.double, n.steps, 1e-9)
        }
        val remapped = p.remapParts(mapOf(0 to 6, 6 to 0, 47 to 12))
        assertEquals(String(res("pattern_a_remapped.prm"), Charsets.ISO_8859_1), remapped.text)
        assertEquals(String(res("pattern_a_cleared.prm"), Charsets.ISO_8859_1), p.cleared().text)
        assertTrue(g["cleared_equals_blank_steps"]!!.jsonPrimitive.boolean)
        // Byte for byte: a pattern that is only moved must be written back unchanged.
        assertTrue(res("pattern_a.prm").contentEquals(p.toBytes()))
    }

    @Test
    fun prmAndNamesMatchTheDesktopApp() {
        val prm = golden["prm"]!!.jsonObject
        assertEquals(prm["render_B3_acid"]!!.jsonPrimitive.content, Prm.render('B', 3, 0, 674, 171870, "Acid", true))
        assertEquals(prm["render_H6_init"]!!.jsonPrimitive.content, Prm.render('H', 6, 0, 1020, 260100))
        for ((input, want) in golden["safe_names"]!!.jsonObject) {
            assertEquals(input, want.jsonPrimitive.content, SafeName.baseName(input, stripTags = true))
        }
    }
}
