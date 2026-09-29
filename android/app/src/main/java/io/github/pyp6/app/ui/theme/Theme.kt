package io.github.pyp6.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

/**
 * The desktop app's eleven palettes, plus "system" (Material You colours
 * from the wallpaper). Each maps onto a Material 3 colour scheme; the few
 * colours Material has no slot for (waveform, mono stripe, over-length
 * orange) travel in [PyP6Colors].
 */
@Immutable
data class Palette(
    val key: String,
    val label: String,
    val dark: Boolean,
    val bgDark: Long, val bgPanel: Long, val bgInput: Long,
    val fgText: Long, val fgMuted: Long,
    val blue: Long, val green: Long, val red: Long, val orange: Long, val purple: Long,
    val border: Long, val borderLight: Long, val waveBg: Long, val wave: Long,
)

val PALETTES = listOf(
    Palette("dark", "Dark", true, 0xFF1E1E24, 0xFF2A2A33, 0xFF33333E, 0xFFE8E8ED, 0xFF9E9EA9,
        0xFF5FA6C0, 0xFF6BB06F, 0xFFDD807C, 0xFFCC8A3D, 0xFF8C5A99, 0xFF3D3D48, 0xFF5C5C6A, 0xFF33333E, 0xFF228EB5),
    Palette("tokyo", "Tokyo", true, 0xFF1A1B26, 0xFF24283B, 0xFF292E42, 0xFFC0CAF5, 0xFF9398B3,
        0xFF7AA2F7, 0xFF9ECE6A, 0xFFF7768E, 0xFFFF9E64, 0xFFBB9AF7, 0xFF3B4261, 0xFF545C7E, 0xFF16161E, 0xFF1D7A9C),
    Palette("dracula", "Dracula", true, 0xFF282A36, 0xFF343746, 0xFF44475A, 0xFFF8F8F2, 0xFFB7BAC5,
        0xFF8BE9FD, 0xFF50FA7B, 0xFFFF7B7B, 0xFFFFB86C, 0xFFBD93F9, 0xFF44475A, 0xFF6272A4, 0xFF21222C, 0xFF1D7A9C),
    Palette("modern", "Modern", true, 0xFF1A1B23, 0xFF22232D, 0xFF2C2D38, 0xFFEDEDF2, 0xFF9697A9,
        0xFF6C8EEF, 0xFF4ADE80, 0xFFF87171, 0xFFFBBF6D, 0xFFC084FC, 0xFF34353F, 0xFF4A4B58, 0xFF2C2D38, 0xFF2087AC),
    Palette("matrix", "Matrix", true, 0xFF070B08, 0xFF0C130D, 0xFF131E14, 0xFFE3E3E3, 0xFF878787,
        0xFF2BE3A0, 0xFF00FF41, 0xFFD4796C, 0xFFC8E24B, 0xFF7BE85B, 0xFF1C2E1F, 0xFF33573A, 0xFF050805, 0xFF0F9E38),
    Palette("ice", "Ice", true, 0xFF04090F, 0xFF0A131E, 0xFF101E2E, 0xFFE4F0F3, 0xFF8EA1AC,
        0xFF22D3F5, 0xFF3FE3C4, 0xFFE8836F, 0xFFE0A550, 0xFF94B4F5, 0xFF1A2E42, 0xFF305C78, 0xFF04080E, 0xFF1E93C8),
    Palette("devil", "Devil", true, 0xFF0D0507, 0xFF170A0C, 0xFF241216, 0xFFEEEEEE, 0xFFAAAAAA,
        0xFFB08FC0, 0xFFD6B26A, 0xFFFF4155, 0xFFE88C55, 0xFFD08FA0, 0xFF3A1A20, 0xFF6E3038, 0xFF0A0406, 0xFFC0424F),
    Palette("synthwave", "Synthwave", true, 0xFF0B0518, 0xFF150A2B, 0xFF221342, 0xFFECEAF1, 0xFFA8A1BA,
        0xFF5B9BFF, 0xFF34E2E2, 0xFFFF3D77, 0xFFFFA53C, 0xFFC77DFF, 0xFF331E5C, 0xFF5A3695, 0xFF090414, 0xFF8A5CF0),
    Palette("latte", "Latte", false, 0xFFE4E6EC, 0xFFF2F3F7, 0xFFD8DBE3, 0xFF3F4256, 0xFF5C5F73,
        0xFF2F5FBF, 0xFF396E2E, 0xFFA8324A, 0xFF9A5518, 0xFF6B44AB, 0xFFC3C6D2, 0xFFACB0BE, 0xFFCCD0DA, 0xFF1D7A9C),
    Palette("snow", "Snow", false, 0xFFECECEC, 0xFFF6F6F6, 0xFFE3E3E3, 0xFF1D1D1F, 0xFF636369,
        0xFF0060C0, 0xFF1B7040, 0xFFB92E29, 0xFF975309, 0xFF6E4BB8, 0xFFDCDCDC, 0xFFC2C2C2, 0xFFE0E0E0, 0xFF1B6E8C),
    Palette("bright", "Bright", false, 0xFFEFF1F5, 0xFFFFFFFF, 0xFFE6E9EF, 0xFF4C4F69, 0xFF5C5F73,
        0xFF1B57CE, 0xFF337524, 0xFFC10E35, 0xFFA85107, 0xFF7B33D6, 0xFFCCD0DA, 0xFFBCC0CC, 0xFFDCE0E8, 0xFF1D7A9C),
)

const val THEME_SYSTEM = "system"

@Immutable
data class PyP6Colors(
    val wave: Color,
    val waveBg: Color,
    val over: Color,
    val mono: Color,
    val good: Color,
    val warn: Color,
    val playing: Color,
    val highlight: Color,
)

val LocalPyP6Colors = staticCompositionLocalOf {
    PyP6Colors(Color(0xFF228EB5), Color(0xFF33333E), Color(0xFFCC8A3D), Color(0xFF8C5A99),
        Color(0xFF6BB06F), Color(0xFFCC8A3D), Color(0xFF5FA6C0), Color(0xFF3E7A3E))
}

private fun on(c: Color): Color = if (c.luminance() > 0.45f) Color(0xFF10131A) else Color.White

private fun Palette.scheme(): ColorScheme {
    val bg = Color(bgDark); val panel = Color(bgPanel); val input = Color(bgInput)
    val text = Color(fgText); val muted = Color(fgMuted)
    val b = Color(blue); val g = Color(green); val r = Color(red); val o = Color(orange)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = b, onPrimary = on(b),
        primaryContainer = input, onPrimaryContainer = text,
        secondary = g, onSecondary = on(g),
        secondaryContainer = input, onSecondaryContainer = text,
        tertiary = o, onTertiary = on(o),
        tertiaryContainer = input, onTertiaryContainer = text,
        error = r, onError = on(r),
        background = bg, onBackground = text,
        surface = bg, onSurface = text,
        surfaceVariant = input, onSurfaceVariant = muted,
        surfaceContainerLowest = bg, surfaceContainerLow = panel,
        surfaceContainer = panel, surfaceContainerHigh = input, surfaceContainerHighest = input,
        surfaceBright = input, surfaceDim = bg,
        inverseSurface = text, inverseOnSurface = bg,
        outline = Color(borderLight), outlineVariant = Color(border),
    )
}

fun paletteFor(key: String): Palette = PALETTES.firstOrNull { it.key == key } ?: PALETTES[0]

@Composable
fun PyP6Theme(theme: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val (scheme, extras) = if (theme == THEME_SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dark = isSystemInDarkTheme()
        val s = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        s to PyP6Colors(
            wave = s.primary, waveBg = s.surfaceContainerHighest, over = s.tertiary, mono = s.secondary,
            good = s.secondary, warn = s.tertiary, playing = s.primary, highlight = s.secondaryContainer,
        )
    } else {
        val p = paletteFor(theme)
        p.scheme() to PyP6Colors(
            wave = Color(p.wave), waveBg = Color(p.waveBg), over = Color(p.orange), mono = Color(p.purple),
            good = Color(p.green), warn = Color(p.orange), playing = Color(p.blue),
            highlight = Color(p.green).copy(alpha = 0.35f),
        )
    }
    CompositionLocalProvider(LocalPyP6Colors provides extras) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}

/** Whether the active scheme is dark (for status bar icons). */
fun isDarkTheme(theme: String, systemDark: Boolean): Boolean =
    if (theme == THEME_SYSTEM) systemDark else paletteFor(theme).dark
