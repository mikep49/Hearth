package dev.hearth.casaos

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hearth.casaos.data.Session
import dev.hearth.casaos.ui.apps.AppsScreen
import dev.hearth.casaos.ui.apps.AppsViewModel
import dev.hearth.casaos.ui.connect.ConnectScreen
import dev.hearth.casaos.ui.connect.ConnectViewModel
import dev.hearth.casaos.ui.dashboard.DashboardScreen
import dev.hearth.casaos.ui.dashboard.DashboardViewModel
import dev.hearth.casaos.ui.files.FilesScreen
import dev.hearth.casaos.ui.files.FilesViewModel
import dev.hearth.casaos.ui.settings.SettingsScreen
import dev.hearth.casaos.ui.settings.SettingsViewModel
import dev.hearth.casaos.ui.terminal.TerminalScreen
import dev.hearth.casaos.ui.terminal.TerminalViewModel
import dev.hearth.casaos.ui.theme.HearthTheme
import dev.hearth.casaos.ui.theme.isDark
import dev.hearth.casaos.ui.web.WebAppScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HearthApp).container
        setContent {
            val theme by container.theme.settings.collectAsStateWithLifecycle()
            val dark = theme.isDark()
            // The app's light/dark choice can differ from the system's, so system bar icons must follow the app.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            HearthTheme(theme) {
                val session by container.session.session.collectAsStateWithLifecycle()
                Crossfade(session, label = "root") { s ->
                    if (s == null) {
                        ConnectScreen(
                            viewModel(factory = viewModelFactory {
                                initializer { ConnectViewModel(container.client, container.session) }
                            }),
                        )
                    } else {
                        MainShell(container, s)
                    }
                }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Dashboard("Home", Icons.Rounded.Dashboard),
    Apps("Apps", Icons.Rounded.Apps),
    Files("Files", Icons.Rounded.Folder),
    Terminal("Terminal", Icons.Rounded.Terminal),
    Settings("Settings", Icons.Rounded.Settings),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainShell(container: AppContainer, session: Session) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    // Keyed by account so signing in to a different server never shows the previous one's data.
    val key = "${session.baseUrl}|${session.username}"
    val label = session.baseUrl.substringAfter("://")
    val openAppsIn by container.prefs.openAppsIn.collectAsStateWithLifecycle()
    // An app's web UI open inside Hearth, drawn over the tabs so they keep their state underneath.
    var webApp by rememberSaveable { mutableStateOf<String?>(null) }
    var webUrl by rememberSaveable { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                // The terminal needs every row it can get while typing.
                val hide = Tab.entries[tab] == Tab.Terminal && WindowInsets.isImeVisible
                if (!hide) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = {
                                if (tab != i) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                tab = i
                            },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            // Consumed so screens that pad for the keyboard don't also count the space the bottom bar already takes.
            Crossfade(Tab.entries[tab], Modifier.padding(padding).consumeWindowInsets(padding), label = "tab") { current ->
                when (current) {
                    Tab.Dashboard -> {
                        val vm: DashboardViewModel = viewModel(key = "dash$key", factory = viewModelFactory {
                            initializer { DashboardViewModel(container.client) }
                        })
                        WhileVisible { vm.poll() }
                        DashboardScreen(vm, session.username, label)
                    }
                    Tab.Apps -> {
                        val vm: AppsViewModel = viewModel(key = "apps$key", factory = viewModelFactory {
                            initializer { AppsViewModel(container.client) }
                        })
                        WhileVisible { vm.poll() }
                        AppsScreen(vm, session.baseUrl, snackbar, openAppsIn) { name, url ->
                            webApp = name
                            webUrl = url
                        }
                    }
                    Tab.Files -> {
                        val vm: FilesViewModel = viewModel(key = "files$key", factory = viewModelFactory {
                            initializer { FilesViewModel(container.client, container.downloader) }
                        })
                        FilesScreen(vm, snackbar)
                    }
                    Tab.Terminal -> {
                        val vm: TerminalViewModel = viewModel(key = "terminal$key", factory = viewModelFactory {
                            initializer { TerminalViewModel(container.ssh, session, container.session.session) }
                        })
                        TerminalScreen(vm)
                    }
                    Tab.Settings -> {
                        val vm: SettingsViewModel = viewModel(key = "settings$key", factory = viewModelFactory {
                            initializer { SettingsViewModel(container.client, container.session, container.theme, container.prefs) }
                        })
                        SettingsScreen(vm, session.username, label, snackbar)
                    }
                }
            }
        }

        val url = webUrl
        if (url != null) {
            WebAppScreen(webApp.orEmpty(), url, onClose = { webUrl = null; webApp = null }, Modifier.fillMaxSize())
        }
    }
}

/** Runs [block] only while the screen is on top and the app is in the foreground. */
@Composable
private fun WhileVisible(block: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { block() }
    }
}
