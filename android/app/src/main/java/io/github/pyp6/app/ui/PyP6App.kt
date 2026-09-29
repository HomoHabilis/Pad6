package io.github.pyp6.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.pyp6.app.ui.screens.BanksScreen
import io.github.pyp6.app.ui.screens.ChopScreen
import io.github.pyp6.app.ui.screens.DeviceScreen
import io.github.pyp6.app.ui.screens.DrawWaveScreen
import io.github.pyp6.app.ui.screens.EditorScreen
import io.github.pyp6.app.ui.screens.OverviewScreen
import io.github.pyp6.app.ui.screens.PadScreen
import io.github.pyp6.app.ui.screens.PatternsScreen
import io.github.pyp6.app.ui.screens.PresetsScreen
import io.github.pyp6.app.ui.screens.SettingsScreen
import io.github.pyp6.app.ui.screens.SynthScreen
import io.github.pyp6.core.model.PadRef

/** The four places the bottom bar leads to. */
enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    PADS("pads", "Pads", Icons.Default.GridView),
    OVERVIEW("overview", "All banks", Icons.Default.Apps),
    PATTERNS("patterns", "Patterns", Icons.Default.ViewModule),
    DEVICE("device", "P-6", Icons.Default.Usb),
}

object Routes {
    fun pad(r: PadRef) = "pad/${r.bank}/${r.pad}"
    fun editor(r: PadRef) = "editor/${r.bank}/${r.pad}"
    fun chop(r: PadRef) = "chop/${r.bank}/${r.pad}"
    fun synth(r: PadRef) = "synth/${r.bank}/${r.pad}"
    const val DRAW = "draw"
    const val PRESETS = "presets"
    const val SETTINGS = "settings"
}

/** Navigation for the whole app; screens get the view model and a way to move on. */
class Nav(val controller: NavHostController) {
    fun to(route: String) = controller.navigate(route) { launchSingleTop = true }
    fun back() = controller.popBackStack()
    fun top(t: TopLevel) = controller.navigate(t.route) {
        popUpTo(TopLevel.PADS.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun PyP6App(vm: MainViewModel, startRoute: String = TopLevel.PADS.route) {
    val controller = rememberNavController()
    val nav = remember(controller) { Nav(controller) }
    val snackbar = remember { SnackbarHostState() }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val alert by vm.alert.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val entry by controller.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = TopLevel.entries.firstOrNull { it.route == route }

    LaunchedEffect(Unit) {
        vm.messages.collect {
            snackbar.showSnackbar(it.text, withDismissAction = it.error,
                duration = if (it.error) SnackbarDuration.Long else SnackbarDuration.Short)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (topLevel != null) NavigationBar {
                TopLevel.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == topLevel,
                        onClick = { nav.top(t) },
                        icon = {
                            if (t == TopLevel.DEVICE && device.reachable) {
                                BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.secondary) }) { Icon(t.icon, null) }
                            } else Icon(t.icon, null)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(controller, startDestination = startRoute, modifier = Modifier.fillMaxSize()) {
                composable(TopLevel.PADS.route) { BanksScreen(vm, nav) }
                composable(TopLevel.OVERVIEW.route) { OverviewScreen(vm, nav) }
                composable(TopLevel.PATTERNS.route) { PatternsScreen(vm, nav) }
                composable(TopLevel.DEVICE.route) { DeviceScreen(vm, nav) }
                val padArgs = listOf(navArgument("bank") { type = NavType.StringType }, navArgument("pad") { type = NavType.IntType })
                composable("pad/{bank}/{pad}", padArgs) { PadScreen(vm, nav, it.padRef()) }
                composable("editor/{bank}/{pad}", padArgs) { EditorScreen(vm, nav, it.padRef()) }
                composable("chop/{bank}/{pad}", padArgs) { ChopScreen(vm, nav, it.padRef()) }
                composable("synth/{bank}/{pad}", padArgs) { SynthScreen(vm, nav, it.padRef()) }
                composable(Routes.DRAW) { DrawWaveScreen(vm, nav) }
                composable(Routes.PRESETS) { PresetsScreen(vm, nav) }
                composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
            }
            AnimatedVisibility(busy != null, modifier = Modifier.align(Alignment.BottomCenter)) {
                Surface(tonalElevation = 6.dp, shadowElevation = 6.dp, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(busy ?: "", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        }
    }

    alert?.let { a ->
        AlertDialog(
            onDismissRequest = vm::dismissAlert,
            title = { Text(a.title) },
            text = { Text(a.text, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = vm::dismissAlert) { Text("OK") } },
        )
    }
}

private fun androidx.navigation.NavBackStackEntry.padRef(): PadRef =
    PadRef(arguments?.getString("bank")?.firstOrNull() ?: 'A', arguments?.getInt("pad") ?: 1)
