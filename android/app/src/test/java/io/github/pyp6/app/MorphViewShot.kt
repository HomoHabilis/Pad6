package io.github.pyp6.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.pyp6.app.ui.components.FamilyGrid
import io.github.pyp6.app.ui.components.MorphView
import io.github.pyp6.app.ui.theme.PyP6Theme
import io.github.pyp6.core.wavetable.Wavetable
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The Synth screen's family grid and Morph display on their own, with a
 * family selected and a step highlighted as during playback. On the Synth
 * screen itself they sit below the fold of a screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class MorphViewShot {
    @Test fun screenshot() {
        val names = Wavetable.SIMPLE_FAMILIES
        val counts = Wavetable.splitSteps(names.size)
        var start = 0
        val zones = names.zip(counts).map { (n, c) -> Triple(start, start + c - 1, n).also { start += c } }
        val shapes = Wavetable.morphShapes(Wavetable.Item.Builtin("Wave Folder"), 36, 1, 0, 12)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            PyP6Theme("dark") {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FamilyGrid(zones, Modifier.fillMaxWidth(), selected = "Wave Folder", playing = "Wave Folder", onTap = {})
                    MorphView(shapes, Modifier.fillMaxWidth().height(150.dp), highlight = 0.45)
                    MorphView(emptyList(), Modifier.fillMaxWidth().height(150.dp), placeholder = "Tap a family to see its morph")
                }
            }
        }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
        val root = activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("pyp6.screenshots") ?: "build/screenshots").also { it.mkdirs() }
        File(dir, "16_morph_view.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
