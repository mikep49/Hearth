package dev.hearth.casaos

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import dev.hearth.casaos.ui.components.PillTab
import dev.hearth.casaos.ui.components.PillTabs
import dev.hearth.casaos.ui.settings.AppearanceScreen
import dev.hearth.casaos.ui.settings.CustomizeHomeScreen
import dev.hearth.casaos.ui.theme.LocalUiStyle
import dev.hearth.casaos.ui.theme.UiStyle
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hearth.casaos.alerts.AlertNotifications
import dev.hearth.casaos.data.AppUsageSource
import dev.hearth.casaos.data.TailscaleSource
import dev.hearth.casaos.data.ServerProfile
import dev.hearth.casaos.data.Session
import dev.hearth.casaos.data.serverRoute
import dev.hearth.casaos.ui.apps.AppLogsScreen
import dev.hearth.casaos.ui.apps.AppLogsViewModel
import dev.hearth.casaos.ui.apps.AppSettingsScreen
import dev.hearth.casaos.ui.apps.AppSettingsViewModel
import dev.hearth.casaos.ui.apps.AppVersionScreen
import dev.hearth.casaos.ui.apps.AppVersionViewModel
import dev.hearth.casaos.ui.apps.AppsScreen
import dev.hearth.casaos.ui.apps.AppsViewModel
import dev.hearth.casaos.ui.apps.StoreScreen
import dev.hearth.casaos.ui.apps.StoreViewModel
import dev.hearth.casaos.ui.components.ClearOfCutout
import dev.hearth.casaos.ui.connect.ConnectScreen
import dev.hearth.casaos.ui.connect.ConnectViewModel
import dev.hearth.casaos.ui.dashboard.DashboardScreen
import dev.hearth.casaos.ui.dashboard.DashboardViewModel
import dev.hearth.casaos.ui.dashboard.TaskManagerScreen
import dev.hearth.casaos.ui.files.FilesScreen
import dev.hearth.casaos.ui.files.FilesViewModel
import dev.hearth.casaos.ui.lock.LockGate
import dev.hearth.casaos.ui.lock.LockedPage
import dev.hearth.casaos.ui.lock.PartLockGate
import dev.hearth.casaos.ui.servers.ServerSheet
import dev.hearth.casaos.ui.settings.SavedLoginsScreen
import dev.hearth.casaos.ui.settings.NotificationsScreen
import dev.hearth.casaos.ui.settings.SecurityScreen
import dev.hearth.casaos.ui.settings.TailscaleScreen
import dev.hearth.casaos.ui.settings.TailscaleViewModel
import dev.hearth.casaos.ui.settings.SettingsScreen
import dev.hearth.casaos.ui.settings.SettingsViewModel
import dev.hearth.casaos.ui.terminal.LockedSignIn
import dev.hearth.casaos.ui.terminal.TerminalScreen
import dev.hearth.casaos.ui.terminal.TerminalViewModel
import dev.hearth.casaos.ui.theme.HearthTheme
import dev.hearth.casaos.ui.theme.isDark
import dev.hearth.casaos.ui.web.WebAppScreen
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// A FragmentActivity because the system unlock prompt needs one. Open for HomeActivity, the same app as the home screen.
open class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HearthApp).container
        // Not again after rotating: the intent is the same, but it was already shown.
        if (savedInstanceState == null) openFrom(intent)
        val homeScreen = this is HomeActivity
        setContent {
            val theme by container.theme.settings.collectAsStateWithLifecycle()
            val dark = theme.isDark()
            // The app's light/dark choice can differ from the system's, so system bar icons must follow the app.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            // Back has nowhere to go from the home screen, and closing it would only make Android start it again.
            // Pages opened over it still close with Back: their handlers come after this one.
            if (homeScreen) BackHandler {}
            HearthTheme(theme) { ClearOfCutout { LockGate(container.lock) { Root(container, homeScreen) } } }
        }
    }

    override fun onStart() {
        super.onStart()
        // While Hearth is the home screen, a Hearth opened beside it, such as from a notification, hands over
        // to it. What it was asked to open is already with the app, for the home screen to show.
        (application as HearthApp).container.launcher.settle(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** A tapped alert opens its server, on Home or Apps. */
    private fun openFrom(intent: Intent?) {
        val server = intent?.getStringExtra(AlertNotifications.EXTRA_SERVER) ?: return
        val apps = intent.getStringExtra(AlertNotifications.EXTRA_TAB) == AlertNotifications.TAB_APPS
        (application as HearthApp).container.openRequest.value = OpenRequest(server, apps)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (application as HearthApp).container.refreshWidgets()
    }
}

/** The sign-in page, or the app once signed in. [homeScreen]: this is the tablet's home screen. */
@Composable
private fun Root(container: AppContainer, homeScreen: Boolean) {
    val session by container.session.session.collectAsStateWithLifecycle()
    val profiles by container.session.profiles.collectAsStateWithLifecycle()
    // Kept out here so switching servers stays on the same tab; a fresh sign-in starts at Home.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(session == null) { if (session == null) tab = 0 }
    val open by container.openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(open) {
        val request = open ?: return@LaunchedEffect
        container.openRequest.value = null
        // A server that was signed out since stays on whatever is showing.
        if (container.session.profile(request.server)?.signedIn != true) return@LaunchedEffect
        container.session.switchTo(request.server)
        tab = if (request.apps) Tab.Apps.ordinal else Tab.Dashboard.ordinal
    }
    Crossfade(session, label = "root") { s ->
        if (s == null) {
            // Offers to sign in again to the server that was open, if it's still saved.
            val last = remember { container.session.lastProfile }
            val context = LocalContext.current
            ConnectScreen(
                viewModel(key = "connect|${last?.id}", factory = viewModelFactory {
                    initializer { ConnectViewModel(container.client, last, container.finder) }
                }),
                saved = profiles,
                onSwitch = { container.session.switchTo(it.id) },
                // Signed out there's no Settings tab, which is where the home screen is turned off.
                onAndroidSettings = if (homeScreen) ({ container.launcher.openAndroidSettings(context) }) else null,
            )
        } else {
            MainShell(container, s, profiles, tab) { tab = it }
        }
    }
}

/** The width from which the tabs move from the bottom bar to a side rail: Material's medium window size. */
private val RailWidth = 600.dp

/** Each tab's icon is outlined, and filled in while the tab is open. */
private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Dashboard("Home", Icons.Outlined.Dashboard, Icons.Rounded.Dashboard),
    Apps("Apps", Icons.Outlined.Apps, Icons.Rounded.Apps),
    Files("Files", Icons.Outlined.Folder, Icons.Rounded.Folder),
    Terminal("Terminal", Icons.Outlined.Terminal, Icons.Rounded.Terminal),
    Settings("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
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
    val homeLayout by container.prefs.homeLayout.collectAsStateWithLifecycle()
    val nickname by container.prefs.nickname.collectAsStateWithLifecycle()
    // An app's web UI open inside Hearth, drawn over the tabs so they keep their state underneath.
    var webApp by rememberSaveable { mutableStateOf<String?>(null) }
    var webUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var webIcon by rememberSaveable { mutableStateOf<String?>(null) }
    // Pages drawn over everything, the tab bar or rail too, so they get the room: an app's settings,
    // version and logs, and the App Store.
    var settingsAppId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsAppName by rememberSaveable { mutableStateOf<String?>(null) }
    var versionAppId by rememberSaveable { mutableStateOf<String?>(null) }
    var versionAppName by rememberSaveable { mutableStateOf<String?>(null) }
    var versionUpdateAvailable by rememberSaveable { mutableStateOf(false) }
    var logsAppId by rememberSaveable { mutableStateOf<String?>(null) }
    var logsAppName by rememberSaveable { mutableStateOf<String?>(null) }
    var storeOpen by rememberSaveable { mutableStateOf(false) }
    var loginsOpen by rememberSaveable { mutableStateOf(false) }
    var securityOpen by rememberSaveable { mutableStateOf(false) }
    var notificationsOpen by rememberSaveable { mutableStateOf(false) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var customizeHomeOpen by rememberSaveable { mutableStateOf(false) }
    var tailscaleOpen by rememberSaveable { mutableStateOf(false) }
    // The Task manager, kept as the server it was opened for, so it closes if another server takes over.
    var taskManagerFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(tab) { if (Tab.entries[tab] != Tab.Dashboard) taskManagerFor = null }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Tablets, and phones on their side, get a rail down the side instead of the bar along the bottom.
        val rail = maxWidth >= RailWidth
        // One UI swaps the bar and the rail for a floating pill.
        val oneUi = LocalUiStyle.current == UiStyle.OneUi
        val pillTabs = remember { Tab.entries.map { PillTab(it.label, it.icon, it.selectedIcon) } }
        fun select(i: Int) {
            if (tab != i) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onTab(i)
        }
        val settingsFactory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    client, container.session, container.theme, container.prefs,
                    container.lock, container.terminalLock, container.appsLock, container.widgetOptions,
                )
            }
        }
        val dashboardFactory = viewModelFactory {
            initializer {
                val server = session.baseUrl.toHttpUrl().host
                DashboardViewModel(client, AppUsageSource({ container.ssh.load(server) }, container.ssh))
            }
        }
        Row(Modifier.fillMaxSize()) {
            if (rail && oneUi) {
                // The pill stands on its end, clear of the edge and the system bars.
                Box(
                    Modifier
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { PillTabs(pillTabs, tab, ::select, vertical = true) }
            } else if (rail) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Spacer(Modifier.weight(1f))
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationRailItem(
                            selected = tab == i,
                            onClick = { select(i) },
                            icon = { Icon(if (tab == i) t.selectedIcon else t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Scaffold(
                // The rail already keeps clear of the system bar on its side.
                modifier = if (rail) Modifier.weight(1f).consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                else Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    // The terminal needs every row it can get while typing.
                    val hide = rail || Tab.entries[tab] == Tab.Terminal && WindowInsets.isImeVisible
                    if (!hide && oneUi) {
                        FloatingTabBar(pillTabs, tab, ::select)
                    } else if (!hide) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Tab.entries.forEachIndexed { i, t ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { select(i) },
                                icon = { Icon(if (tab == i) t.selectedIcon else t.icon, null) },
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
                            val vm: DashboardViewModel = viewModel(key = "dash$key", factory = dashboardFactory)
                            WhileVisible { vm.poll() }
                            val route = remember(session.baseUrl) { serverRoute(session.baseUrl.toHttpUrl().host) }
                            DashboardScreen(
                                vm, nickname.trim().ifBlank { session.username }, label, route, homeLayout,
                                onOpenServers = { serversOpen = true },
                                onOpenTaskManager = { taskManagerFor = session.id },
                                onCustomize = { customizeHomeOpen = true },
                            )
                        }
                        Tab.Apps -> {
                            val vm: AppsViewModel = viewModel(key = "apps$key", factory = viewModelFactory {
                                initializer { AppsViewModel(client) }
                            })
                            WhileVisible { vm.poll() }
                            AppsScreen(
                                vm, session.baseUrl, snackbar, openAppsIn,
                                pageOpen = settingsAppId != null || versionAppId != null || logsAppId != null || storeOpen,
                                onOpenStore = { storeOpen = true },
                                onOpenSettings = { app -> settingsAppId = app.id; settingsAppName = app.name },
                                onOpenVersion = { app ->
                                    versionAppId = app.id
                                    versionAppName = app.name
                                    versionUpdateAvailable = app.updateAvailable
                                },
                                onOpenLogs = { app -> logsAppId = app.id; logsAppName = app.name },
                            ) { app, url ->
                                webApp = app.name
                                webIcon = app.iconUrl
                                webUrl = url
                            }
                        }
                        Tab.Files -> {
                            val vm: FilesViewModel = viewModel(key = "files$key", factory = viewModelFactory {
                                initializer {
                                    FilesViewModel(
                                        client, container.downloader(client), container.phoneFiles(client), container.prefs,
                                        container.transfers, inFront = container::inFront,
                                    )
                                }
                            })
                            val locked by container.lock.locked.collectAsStateWithLifecycle()
                            FilesScreen(vm, snackbar, locked = locked)
                        }
                        Tab.Terminal -> {
                            val vm: TerminalViewModel = viewModel(key = "terminal$key", factory = viewModelFactory {
                                initializer { TerminalViewModel(container.ssh, container.prefs, session, container.session.session) }
                            })
                            // With a login ready and no shell open, the lock asks when Connect is tapped, not before.
                            val terminal by vm.state.collectAsStateWithLifecycle()
                            val signIn: LockedPage = { error, unlock -> LockedSignIn(vm, error, unlock) }
                            // The shell (in the view model) keeps running while the tab is locked or closed.
                            PartLockGate(
                                container.terminalLock, container.lock, "Terminal", Modifier.fillMaxSize(),
                                lockedPage = signIn.takeIf { terminal.canQuickConnect },
                            ) { TerminalScreen(vm) }
                        }
                        Tab.Settings -> {
                            val vm: SettingsViewModel = viewModel(key = "settings$key", factory = settingsFactory)
                            val loginsRemembered by container.webLogins.remember.collectAsStateWithLifecycle()
                            SettingsScreen(
                                vm, session.username, label, snackbar,
                                onOpenServers = { serversOpen = true },
                                loginsRemembered = loginsRemembered,
                                onOpenLogins = { loginsOpen = true },
                                pageOpen = securityOpen || notificationsOpen || appearanceOpen || tailscaleOpen,
                                onOpenAppearance = { appearanceOpen = true },
                                onOpenNotifications = { notificationsOpen = true },
                                onOpenSecurity = { securityOpen = true },
                                tailscale = container.phoneTailscale,
                                onOpenTailscale = { tailscaleOpen = true },
                                launcher = container.launcher,
                            )
                        }
                    }
                }
            }
        }

        val url = webUrl
        if (url != null) {
            val close = { webUrl = null; webApp = null; webIcon = null }
            val strict by container.prefs.strictBrowser.collectAsStateWithLifecycle()
            PartLockGate(container.appsLock, container.lock, webApp.orEmpty().ifBlank { "This app" }, Modifier.fillMaxSize(), onBack = close) {
                WebAppScreen(webApp.orEmpty(), url, container.webLogins, onClose = close, Modifier.fillMaxSize(), iconUrl = webIcon, strict = strict)
            }
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

        if (taskManagerFor == session.id && Tab.entries[tab] == Tab.Dashboard) {
            // The same view model as Home underneath, which keeps reading the server while this is open.
            val vm: DashboardViewModel = viewModel(key = "dash$key", factory = dashboardFactory)
            TaskManagerScreen(
                vm,
                onClose = { taskManagerFor = null },
                onOpenTerminal = { taskManagerFor = null; onTab(Tab.Terminal.ordinal) },
                Modifier.fillMaxSize(),
            )
        }

        if (loginsOpen) {
            // SSH passwords and keys are kept per server address, and only while that server is signed in.
            val servers = remember(profiles) { profiles.filter { it.signedIn }.mapNotNull { it.baseUrl.toHttpUrlOrNull()?.host }.distinct() }
            SavedLoginsScreen(container.webLogins, container.ssh, servers, onClose = { loginsOpen = false }, Modifier.fillMaxSize())
        }

        if (appearanceOpen) {
            // The same view model as the Settings tab underneath.
            val vm: SettingsViewModel = viewModel(key = "settings$key", factory = settingsFactory)
            AppearanceScreen(vm, onClose = { appearanceOpen = false }, onCustomizeHome = { customizeHomeOpen = true }, Modifier.fillMaxSize())
        }

        // Opened from Home or from the Appearance page, and drawn over either, so Back returns to where it was opened.
        if (customizeHomeOpen) {
            CustomizeHomeScreen(homeLayout, container.prefs::setHomeLayout, onClose = { customizeHomeOpen = false }, Modifier.fillMaxSize())
        }

        if (notificationsOpen) {
            // The same view model as the Settings tab underneath.
            val vm: SettingsViewModel = viewModel(key = "settings$key", factory = settingsFactory)
            NotificationsScreen(vm, onClose = { notificationsOpen = false }, Modifier.fillMaxSize())
        }

        if (securityOpen) {
            // The same view model as the Settings tab underneath.
            val vm: SettingsViewModel = viewModel(key = "settings$key", factory = settingsFactory)
            SecurityScreen(vm, onClose = { securityOpen = false }, Modifier.fillMaxSize())
        }

        if (tailscaleOpen) {
            val vm: TailscaleViewModel = viewModel(key = "tailscale$key", factory = viewModelFactory {
                initializer {
                    val server = session.baseUrl.toHttpUrl().host
                    TailscaleViewModel(server, TailscaleSource({ container.ssh.load(server) }, container.ssh), container.phoneTailscale)
                }
            })
            // The server's side is read over SSH, and only while this page is open.
            WhileVisible { vm.poll() }
            TailscaleScreen(
                vm,
                onClose = { tailscaleOpen = false },
                onOpenTerminal = { tailscaleOpen = false; onTab(Tab.Terminal.ordinal) },
                Modifier.fillMaxSize(),
            )
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

        val logsId = logsAppId
        if (logsId != null) {
            val vm: AppLogsViewModel = viewModel(key = "applogs$key|$logsId", factory = viewModelFactory {
                initializer { AppLogsViewModel(client, logsId) }
            })
            AppLogsScreen(
                vm,
                logsId,
                logsAppName.orEmpty(),
                onClose = { logsAppId = null; logsAppName = null },
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

/**
 * One UI's tab bar: the pill, floating clear of the bottom edge. The page fades out just above it, so
 * lists seem to slip away under the pill rather than stop at a line.
 */
@Composable
private fun FloatingTabBar(tabs: List<PillTab>, selected: Int, onSelect: (Int) -> Unit) {
    val background = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val fade = 12.dp.toPx()
                drawRect(
                    Brush.verticalGradient(listOf(background.copy(alpha = 0f), background), startY = -fade, endY = 0f),
                    topLeft = Offset(0f, -fade),
                    size = Size(size.width, fade),
                )
                drawRect(background)
            }
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Capped so the tabs don't drift apart on a wide phone.
        PillTabs(tabs, selected, onSelect, Modifier.widthIn(max = 440.dp).fillMaxWidth())
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
