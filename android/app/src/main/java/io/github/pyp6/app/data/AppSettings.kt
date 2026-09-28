package io.github.pyp6.app.data

import android.content.Context
import io.github.pyp6.core.dsp.StretchMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val theme: String = "dark",
    /** Tree URI of the P-6 drive (or a folder on it) the user granted. */
    val p6TreeUri: String? = null,
    /** Tree URI of the folder presets are saved in; null = inside the app. */
    val presetsTreeUri: String? = null,
    val patternSync: Boolean = false,
    val stretchMethod: StretchMethod = StretchMethod.AUTO,
    val includePrm: Boolean = true,
    val defaultSlices: Int = 16,
    val storageWarningMb: Int = 10,
    val autoplay: Boolean = true,
    val recentPresets: List<String> = emptyList(),
    val lastBank: Char = 'A',
)

/** Settings in SharedPreferences, observable as a StateFlow. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("pyp6", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    private fun read() = Settings(
        theme = prefs.getString("theme", "dark") ?: "dark",
        p6TreeUri = prefs.getString("p6TreeUri", null),
        presetsTreeUri = prefs.getString("presetsTreeUri", null),
        patternSync = prefs.getBoolean("patternSync", false),
        stretchMethod = StretchMethod.fromKey(prefs.getString("stretchMethod", "auto")),
        includePrm = prefs.getBoolean("includePrm", true),
        defaultSlices = prefs.getInt("defaultSlices", 16),
        storageWarningMb = prefs.getInt("storageWarningMb", 10),
        autoplay = prefs.getBoolean("autoplay", true),
        recentPresets = prefs.getString("recentPresets", "")!!.split('\n').filter { it.isNotBlank() },
        lastBank = (prefs.getString("lastBank", "A") ?: "A").firstOrNull() ?: 'A',
    )

    fun update(block: (Settings) -> Settings) {
        val s = block(_state.value)
        prefs.edit()
            .putString("theme", s.theme)
            .putString("p6TreeUri", s.p6TreeUri)
            .putString("presetsTreeUri", s.presetsTreeUri)
            .putBoolean("patternSync", s.patternSync)
            .putString("stretchMethod", s.stretchMethod.key)
            .putBoolean("includePrm", s.includePrm)
            .putInt("defaultSlices", s.defaultSlices)
            .putInt("storageWarningMb", s.storageWarningMb)
            .putBoolean("autoplay", s.autoplay)
            .putString("recentPresets", s.recentPresets.joinToString("\n"))
            .putString("lastBank", s.lastBank.toString())
            .apply()
        _state.value = s
    }

    fun addRecentPreset(id: String) = update { s ->
        s.copy(recentPresets = (listOf(id) + s.recentPresets.filter { it != id }).take(5))
    }
}
