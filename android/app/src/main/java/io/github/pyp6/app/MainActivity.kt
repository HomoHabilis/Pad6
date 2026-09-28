package io.github.pyp6.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pyp6.app.ui.MainViewModel
import io.github.pyp6.app.ui.PyP6App
import io.github.pyp6.app.ui.theme.PyP6Theme
import io.github.pyp6.app.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    companion object {
        /** Opens the app on a given screen route (used by the screenshot tests). */
        const val EXTRA_START = "io.github.pyp6.START_ROUTE"
    }

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val dark = isDarkTheme(settings.theme, isSystemInDarkTheme())
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            PyP6Theme(settings.theme) {
                PyP6App(vm, startRoute = intent?.getStringExtra(EXTRA_START) ?: io.github.pyp6.app.ui.TopLevel.PADS.route)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as PyP6Application).container.device.setForeground(true)
    }

    override fun onStop() {
        (application as PyP6Application).container.device.setForeground(false)
        super.onStop()
    }
}
