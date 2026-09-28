package io.github.pyp6.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import io.github.pyp6.core.audio.Audio
import io.github.pyp6.core.audio.Wav
import io.github.pyp6.core.model.PadRef
import io.github.pyp6.core.model.PadState
import io.github.pyp6.core.model.Project
import io.github.pyp6.core.model.WavetableState
import io.github.pyp6.core.model.WtConfig
import io.github.pyp6.core.pattern.P6Pattern
import io.github.pyp6.core.pattern.PatternSlot
import io.github.pyp6.core.prm.Prm
import io.github.pyp6.core.wavetable.Wavetable
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Renders every screen at phone size with realistic content and writes PNGs
 * to build/screenshots, so the layouts can be checked without a device:
 * nothing cut off, nothing overlapping, everything reachable.
 *
 * The default size is a Pixel Pro class phone (411 x 914 dp); the
 * SmallPhone class repeats the main screens at 360 x 740 dp with larger
 * text, the tightest case the layouts should survive.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
abstract class ScreenshotTest(private val name: String, private val route: String, private val bank: Char = 'A') {
    protected open val suffix = ""

    // One screen per class: Compose keeps global state bound to the first
    // test's main looper, so every screenshot gets a JVM of its own
    // (forkEvery = 1 in build.gradle.kts).
    @Test fun screenshot() = shoot(name, route, bank)

    private fun app() = RuntimeEnvironment.getApplication() as PyP6Application

    private fun tone(seconds: Double, hz: Double, rate: Int = 44100, decay: Double = 3.0, channels: Int = 1): Audio {
        val n = (seconds * rate).toInt()
        val data = FloatArray(n) { i ->
            val t = i.toDouble() / rate
            (0.8 * sin(2 * PI * hz * t) * exp(-t * decay) + 0.2 * sin(2 * PI * hz * 2.01 * t) * exp(-t * decay * 2)).toFloat()
        }
        return Audio(rate, List(channels) { data })
    }

    private fun drums(seconds: Double, bpm: Double, rate: Int = 44100): Audio {
        val n = (seconds * rate).toInt()
        val beat = 60.0 / bpm * rate
        val rnd = java.util.Random(1)
        val data = FloatArray(n)
        var b = 0
        while (b * beat < n) {
            val s = (b * beat).toInt()
            for (i in 0 until minOf(8000, n - s)) {
                val t = i.toDouble() / rate
                data[s + i] += (sin(2 * PI * (50 + 80 * exp(-t * 40)) * t) * exp(-t * 12)).toFloat() * 0.8f
                if (b % 2 == 1) data[s + i] += ((rnd.nextGaussian() * 0.4) * exp(-t * 25)).toFloat()
            }
            b++
        }
        return Audio(rate, listOf(data))
    }

    private fun write(a: Audio, name: String): File {
        val f = app().container.store.newFile(name, "imp")
        Wav.writePcm16(a, f)
        return f
    }

    /** A pattern that plays a few pads, built from the P-6's own blank pattern. */
    private fun pattern(parts: List<Pair<Int, Int>>, length: Int = 16): P6Pattern {
        val lines = P6Pattern.blankText.split("\n").map { line ->
            val key = line.substringBefore('\t')
            if (key.startsWith("STEP_NOTE_SMPL ")) {
                val step = key.substringAfterLast(' ').toInt()
                val hits = parts.filter { (_, s) -> s == step }
                var out = line
                hits.forEachIndexed { i, (part, _) ->
                    val k = i + 1
                    out = out.replace("PART$k=-1 NOTE$k=-1 VELO$k=0 LENG$k=0", "PART$k=$part NOTE$k=60 VELO$k=100 LENG$k=80")
                }
                out
            } else if (key == "LENG") "LENG\t= $length" else line
        }
        return P6Pattern(lines.joinToString("\n"))
    }

    private fun seed() {
        val c = app().container
        val names = listOf("Kick 909", "Snare Tight", "Closed Hat", "Open Hat", "Clap", "Rim")
        var p = Project()
        names.forEachIndexed { i, n ->
            val f = write(if (i < 2) drums(0.6, 120.0) else tone(0.4 + i * 0.1, 200.0 + 80 * i), "$n.wav")
            p = p.withPad(PadRef('A', i + 1), c.store.newPadState(f, "$n.wav"))
        }
        val loop = write(drums(8.0, 90.0), "Break 90bpm.wav")
        p = p.withPad(PadRef('B', 1), c.store.newPadState(loop, "Break 90bpm.wav").copy(target_rate = 44100))
        val pad = write(tone(3.0, 110.0, decay = 0.2, channels = 2), "Warm Pad.wav")
        c.store.attachPrm(pad, Prm.render('B', 2, 0, 1000, 132300).toByteArray())
        p = p.withPad(PadRef('B', 2), c.store.newPadState(pad, "Warm Pad.wav").copy(pitch_cents = -300, mono = true))
        val wt = Wavetable.build(Wavetable.SIMPLE_FAMILIES.map { Wavetable.Item.Builtin(it) }, 36, 1, 0)
        val wtFile = c.store.newFile("wavetable", "wt", ".WAV")
        Wav.writePcm16Mono(wt.pcm, 44100, wtFile)
        p = p.withPad(PadRef('B', 3), PadState(wtFile.absolutePath, display_name = "Synth Mode",
            wavetable = WavetableState(WtConfig(families = Wavetable.SIMPLE_FAMILIES), wt.meta)))
        for (b in listOf('C', 'E')) for (i in 1..4) {
            p = p.withPad(PadRef(b, i), c.store.newPadState(write(tone(0.3, 300.0 + 40 * i), "Tone $b$i.wav"), "Tone $b$i.wav"))
        }
        p = p.withBank('E', p.bank('E').copy(forceMono = true))
        val pats = sortedMapOf(
            PatternSlot(1, 1) to pattern(listOf(0 to 1, 2 to 3, 1 to 5, 2 to 7, 0 to 9, 2 to 11, 1 to 13, 2 to 15)),
            PatternSlot(1, 2) to pattern(listOf(6 to 1, 7 to 5, 6 to 9, 12 to 13), 32),
            PatternSlot(1, 5) to P6Pattern.blank(),
            PatternSlot(2, 3) to pattern(listOf(8 to 1, 8 to 3, 24 to 9)),
        )
        p = p.copy(patterns = pats, patternSource = "P-6 BACKUP")
        c.repository.update { p }
        c.settings.update { it.copy(patternSync = true) }
    }

    private fun idle(ms: Long = 400) {
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms / 6)) }
    }

    private fun shoot(name: String, route: String, bank: Char = 'A') {
        seed()
        app().container.settings.update { it.copy(lastBank = bank) }
        val intent = Intent(app(), MainActivity::class.java).putExtra(MainActivity.EXTRA_START, route)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()
        // Waveform summaries and wavetable previews load off the main thread.
        repeat(30) {
            idle(120)
            Thread.sleep(150)
        }
        idle(800)
        val root: View = activity.window.decorView
        assertTrue("screen $name has no size", root.width > 0 && root.height > 0)
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("pyp6.screenshots") ?: "build/screenshots").also { it.mkdirs() }
        File(dir, "$name$suffix.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }
}

private const val SMALL = "w360dp-h740dp-xhdpi"

/** Small phone with the system font at 130 %: the tightest case the layouts should survive. */
@Config(sdk = [35], qualifiers = SMALL, fontScale = 1.3f)
abstract class SmallScreenshotTest(name: String, route: String, bank: Char = 'A') : ScreenshotTest(name, route, bank) {
    override val suffix = "_small"
}

class PadsShot : ScreenshotTest("01_pads", "pads")
class PadsBankBShot : ScreenshotTest("02_pads_bank_b", "pads", 'B')
class OverviewShot : ScreenshotTest("03_overview", "overview")
class PatternsShot : ScreenshotTest("04_patterns", "patterns")
class DeviceShot : ScreenshotTest("05_device", "device")
class PadShot : ScreenshotTest("06_pad", "pad/B/2")
class PadWavetableShot : ScreenshotTest("07_pad_wavetable", "pad/B/3")
class EditorShot : ScreenshotTest("08_editor", "editor/B/1")
class ChopShot : ScreenshotTest("09_chop", "chop/A/6")
class SynthShot : ScreenshotTest("10_synth", "synth/B/3")
class DrawShot : ScreenshotTest("11_draw", "draw")
class PresetsShot : ScreenshotTest("12_presets", "presets")
class SettingsShot : ScreenshotTest("13_settings", "settings")
class EmptyPadShot : ScreenshotTest("14_pad_empty", "pad/D/1")

class PadsSmallShot : SmallScreenshotTest("01_pads", "pads", 'B')
class OverviewSmallShot : SmallScreenshotTest("03_overview", "overview")
class PatternsSmallShot : SmallScreenshotTest("04_patterns", "patterns")
class DeviceSmallShot : SmallScreenshotTest("05_device", "device")
class PadSmallShot : SmallScreenshotTest("06_pad", "pad/B/2")
class EditorSmallShot : SmallScreenshotTest("08_editor", "editor/B/1")
class ChopSmallShot : SmallScreenshotTest("09_chop", "chop/A/6")
class SynthSmallShot : SmallScreenshotTest("10_synth", "synth/B/3")
