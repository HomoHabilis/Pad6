package io.github.pyp6.app

import android.app.Application
import io.github.pyp6.app.data.AppSettings
import io.github.pyp6.app.data.AudioImporter
import io.github.pyp6.app.data.DeviceMonitor
import io.github.pyp6.app.data.Player
import io.github.pyp6.app.data.ProjectRepository
import io.github.pyp6.core.model.SampleStore
import io.github.pyp6.core.wavetable.WaveLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Process-wide objects, created once. A hand-rolled container: the app is small enough not to need DI. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = AppSettings(app)
    val store = SampleStore(File(app.filesDir, "samples"))
    val repository = ProjectRepository(File(app.filesDir, "project.json"), scope)
    val player = Player(scope)
    val importer = AudioImporter(app, store)
    val device = DeviceMonitor(app, settings, scope)
    val waveLibrary = WaveLibrary(File(app.filesDir, "waveforms.json"))
    val internalPresets = File(app.filesDir, "presets").also { it.mkdirs() }
}

class PyP6Application : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
