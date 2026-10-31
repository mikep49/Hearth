package dev.hearth.casaos

import android.graphics.Color
import android.os.Bundle
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
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hearth.casaos.data.AppUsageSource
import dev.hearth.casaos.data.ServerProfile
import dev.hearth.casaos.data.Session
import dev.hearth.casaos.ui.apps.AppSettingsScreen
import dev.hearth.casaos.ui.apps.AppSettingsViewModel
import dev.hearth.casaos.ui.apps.AppVersionScreen
import dev.hearth.casaos.ui.apps.AppVersionViewModel
import dev.hearth.casaos.ui.apps.AppsScreen
import dev.hearth.casaos.ui.apps.AppsViewModel
import dev.hearth.casaos.ui.apps.StoreScreen
import dev.hearth.casaos.ui.apps.StoreViewModel
import dev.hearth.casaos.ui.connect.ConnectScreen
import dev.hearth.casaos.ui.connect.ConnectViewModel
import dev.hearth.casaos.ui.dashboard.DashboardScreen
import dev.hearth.casaos.ui.dashboard.DashboardViewModel
import dev.hearth.casaos.ui.files.FilesScreen
import dev.hearth.casaos.ui.files.FilesViewModel
import dev.hearth.casaos.ui.lock.LockGate
import dev.hearth.casaos.ui.servers.ServerSheet
import dev.hearth.casaos.ui.settings.SettingsScreen
import dev.hearth.casaos.ui.settings.SettingsViewModel
import dev.hearth.casaos.ui.terminal.TerminalScreen
import dev.hearth.casaos.ui.terminal.TerminalViewModel
import dev.hearth.casaos.ui.theme.HearthTheme
import dev.hearth.casaos.ui.theme.isDark
import dev.hearth.casaos.ui.web.WebAppScreen
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// A FragmentActivity because the system unlock prompt needs one.
class MainActivity : FragmentActivity() {
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
            HearthTheme(theme) { LockGate(container.lock) { Root(container) } }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (application as HearthApp).container.refreshWidgets()
    }
}

/** The sign-in page, or the app once signed in. */
@Composable
private fun Root(container: AppContainer) {
    val session by container.session.session.collectAsStateWithLifecycle()
    val profiles by container.session.profiles.collectAsStateWithLifecycle()
    // Kept out here so switching servers stays on the same tab; a fresh sign-in starts at Home.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(session == null) { if (session == null) tab = 0 }
    Crossfade(session, label = "root") { s ->
        if (s == null) {
            // Offers to sign in again to the server that was open, if it's still saved.
            val last = remember { container.session.lastProfile }
            ConnectScreen(
                viewModel(key = "connect|${last?.id}", factory = viewModelFactory {
                    initializer { ConnectViewModel(container.client, last, container.finder) }
                }),
                saved = profiles,
                onSwitch = { container.session.switchTo(it.id) },
            )
        } else {
            MainShell(container, s, profiles, tab) { tab = it }
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
private fun MainShell(container: AppContainer, session: Session, profiles: List<ServerProfile>, tab: Int, onTab: (Int) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    // Keyed by account so switching servers never shows the previous one's data. Each server's screens get
    // that server's own client, so anything still running after a switch keeps talking to the right one.
    val key = session.id
    val client = remember(session.id) { container.clientFor(session.id) }
    val label = session.label
    // The server switcher, and the "Add a server" page (with a saved server to sign in to again, if any).
    var serversOpen by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var addingPrefill by rememberSaveable { mutableStateOf<String?>(null) }
    var addCount by rememberSaveable { mutableIntStateOf(0) }
    fun startAdding(prefill: ServerProfile?) {
        serversOpen = false
        addingPrefill = prefill?.id
        addCount++
        adding = true
    }
    val openAppsIn by container.prefs.openAppsIn.collectAsStateWithLifecycle()
    // An app's web UI open inside Hearth, drawn over the tabs so they keep their state underneath.
    var webApp by rememberSaveable { mutableStateOf<String?>(null) }
    var webUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var webIcon by rememberSaveable { mutableStateOf<String?>(null) }
    // An app's settings page, also drawn over everything (the bottom bar too, so the form gets the room).
    var settingsAppId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsAppName by rememberSaveable { mutableStateOf<String?>(null) }
    // An app's version page, drawn over everything the same way.
    var versionAppId by rememberSaveable { mutableStateOf<String?>(null) }
    var versionAppName by rememberSaveable { mutableStateOf<String?>(null) }
    var versionUpdateAvailable by rememberSaveable { mutableStateOf(false) }
    // The App Store, drawn over everything the same way.
    var storeOpen by rememberSaveable { mutableStateOf(false) }

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
                                onTab(i)
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
                            initializer {
                                val server = session.baseUrl.toHttpUrl().host
                                DashboardViewModel(
                                    client,
                                    AppUsageSource({ container.ssh.load(server) }, container.ssh),
                                    container.prefs.appsCardCollapsed,
                                )
                            }
                        })
                        WhileVisible { vm.poll() }
                        val appsCollapsed by container.prefs.appsCardCollapsed.collectAsStateWithLifecycle()
                        DashboardScreen(
                            vm, session.username, label,
                            onOpenServers = { serversOpen = true },
                            appsCollapsed = appsCollapsed,
                            onAppsCollapsedChange = container.prefs::setAppsCardCollapsed,
                            onOpenTerminal = { onTab(Tab.Terminal.ordinal) },
                        )
                    }
                    Tab.Apps -> {
                        val vm: AppsViewModel = viewModel(key = "apps$key", factory = viewModelFactory {
                            initializer { AppsViewModel(client) }
                        })
                        WhileVisible { vm.poll() }
                        AppsScreen(
                            vm, session.baseUrl, snackbar, openAppsIn,
                            pageOpen = settingsAppId != null || versionAppId != null || storeOpen,
                            onOpenStore = { storeOpen = true },
                            onOpenSettings = { app -> settingsAppId = app.id; settingsAppName = app.name },
                            onOpenVersion = { app ->
                                versionAppId = app.id
                                versionAppName = app.name
                                versionUpdateAvailable = app.updateAvailable
                            },
                        ) { app, url ->
                            webApp = app.name
                            webIcon = app.iconUrl
                            webUrl = url
                        }
                    }
                    Tab.Files -> {
                        val vm: FilesViewModel = viewModel(key = "files$key", factory = viewModelFactory {
                            initializer { FilesViewModel(client, container.downloader(client), container.phoneFiles(client), container.prefs) }
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
                            initializer { SettingsViewModel(client, container.session, container.theme, container.prefs, container.lock) }
                        })
                        SettingsScreen(vm, session.username, label, snackbar, onOpenServers = { serversOpen = true })
                    }
                }
            }
        }

        val url = webUrl
        if (url != null) {
            WebAppScreen(
                webApp.orEmpty(),
                url,
                onClose = { webUrl = null; webApp = null; webIcon = null },
                Modifier.fillMaxSize(),
                iconUrl = webIcon,
            )
        }

        val settingsId = settingsAppId
        if (settingsId != null) {
            val vm: AppSettingsViewModel = viewModel(key = "appsettings$key|$settingsId", factory = viewModelFactory {
                initializer { AppSettingsViewModel(client, settingsId) }
            })
            AppSettingsScreen(
                vm,
                settingsAppName.orEmpty(),
                serverHost = session.baseUrl.toHttpUrlOrNull()?.host.orEmpty(),
                onClose = { settingsAppId = null; settingsAppName = null },
                Modifier.fillMaxSize(),
            )
        }

        if (storeOpen) {
            val vm: StoreViewModel = viewModel(key = "store$key", factory = viewModelFactory {
                initializer { StoreViewModel(client) }
            })
            StoreScreen(vm, onClose = { storeOpen = false }, Modifier.fillMaxSize())
        }

        val versionId = versionAppId
        if (versionId != null) {
            val vm: AppVersionViewModel = viewModel(key = "appversion$key|$versionId", factory = viewModelFactory {
                initializer { AppVersionViewModel(client, container.registry, versionId) }
            })
            AppVersionScreen(
                vm,
                versionAppName.orEmpty(),
                updateAvailable = versionUpdateAvailable,
                onClose = { versionAppId = null; versionAppName = null },
                Modifier.fillMaxSize(),
            )
        }

        if (adding) {
            val prefill = addingPrefill?.let { id -> profiles.firstOrNull { it.id == id } }
            // A new key each time it opens, so it starts empty rather than where the last one left off.
            val vm: ConnectViewModel = viewModel(key = "connect-add$addCount", factory = viewModelFactory {
                initializer { ConnectViewModel(container.client, prefill, container.finder) }
            })
            ConnectScreen(vm, onClose = { adding = false })
        }
    }

    if (serversOpen) {
        ServerSheet(
            profiles,
            currentId = session.id,
            onSwitch = { serversOpen = false; container.session.switchTo(it.id) },
            onSignInAgain = { startAdding(it) },
            onAdd = { startAdding(null) },
            onRemove = { container.session.remove(it.id) },
            onDismiss = { serversOpen = false },
        )
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
