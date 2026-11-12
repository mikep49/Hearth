package dev.hearth.casaos.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hearth.casaos.BuildConfig
import dev.hearth.casaos.data.AlertSettings
import dev.hearth.casaos.data.AppLock
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.LockAfter
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.OpenAppsIn
import dev.hearth.casaos.data.PowerAction
import dev.hearth.casaos.data.PrefsStore
import dev.hearth.casaos.data.SessionStore
import androidx.compose.ui.text.style.TextAlign
import dev.hearth.casaos.ui.components.Chevron
import dev.hearth.casaos.ui.components.ListGroup
import dev.hearth.casaos.ui.components.ListRow
import dev.hearth.casaos.ui.components.ScreenHeader
import dev.hearth.casaos.ui.components.SectionHeader
import dev.hearth.casaos.ui.components.SwitchRow
import dev.hearth.casaos.ui.theme.ThemeSettings
import dev.hearth.casaos.ui.theme.ThemeStore
import dev.hearth.casaos.widget.WidgetConfigActivity
import dev.hearth.casaos.widget.WidgetOptions
import dev.hearth.casaos.widget.WidgetOptionsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dev.hearth.casaos.ui.components.readableWidth

class SettingsViewModel(
    private val client: CasaClient,
    private val session: SessionStore,
    private val themeStore: ThemeStore,
    private val prefs: PrefsStore,
    private val lock: AppLock,
    private val terminalLock: AppLock,
    private val appsLock: AppLock,
    widgetOptions: WidgetOptionsStore,
) : ViewModel() {
    private val _version = MutableStateFlow<String?>(null)
    val version: StateFlow<String?> = _version.asStateFlow()

    val theme: StateFlow<ThemeSettings> = themeStore.settings

    fun setTheme(settings: ThemeSettings) = themeStore.update(settings)

    /** How each home screen widget is set up, by app widget id. */
    val widgetOptions: StateFlow<Map<Int, WidgetOptions>> = widgetOptions.options

    val openAppsIn: StateFlow<OpenAppsIn> = prefs.openAppsIn

    fun setOpenAppsIn(value: OpenAppsIn) = prefs.setOpenAppsIn(value)

    val alerts: StateFlow<AlertSettings> = prefs.alerts

    fun setAlerts(value: AlertSettings) = prefs.setAlerts(value)

    val lockEnabled: StateFlow<Boolean> = lock.enabled
    val lockAfter: StateFlow<LockAfter> = lock.lockAfter

    fun lockAvailable(): Boolean = lock.available()

    fun setLockEnabled(value: Boolean) {
        lock.setEnabled(value)
        // The Files app can't ask for the lock, so turning it on takes Hearth out of there.
        if (value && prefs.showInFiles.value) {
            prefs.setShowInFiles(false)
            _messages.tryEmit("Turned off Show in Android Files")
        }
    }

    val terminalLocked: StateFlow<Boolean> = terminalLock.enabled
    val appsLocked: StateFlow<Boolean> = appsLock.enabled

    fun setTerminalLocked(value: Boolean) = setPartLock(terminalLock, value)
    fun setAppsLocked(value: Boolean) = setPartLock(appsLock, value)

    private fun setPartLock(part: AppLock, value: Boolean) {
        part.setEnabled(value)
        // Settings is in front, so the part is closed: it asks from the next time it's opened.
        if (value) part.lock()
    }

    val strictBrowser: StateFlow<Boolean> = prefs.strictBrowser

    fun setStrictBrowser(value: Boolean) = prefs.setStrictBrowser(value)

    val showInFiles: StateFlow<Boolean> = prefs.showInFiles

    fun setShowInFiles(value: Boolean) = prefs.setShowInFiles(value)

    fun setLockAfter(value: LockAfter) = lock.setLockAfter(value)

    fun showError(message: String) {
        _messages.tryEmit(message)
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { _version.value = try { client.version() } catch (_: CasaException) { null } }
    }

    fun power(action: PowerAction) {
        viewModelScope.launch {
            try {
                client.power(action)
                _messages.emit(if (action == PowerAction.Restart) "Server is restarting…" else "Server is shutting down…")
            } catch (e: CasaException) {
                _messages.emit("Couldn't send command: ${e.message}")
            }
        }
    }

    fun signOut() = session.signOut()
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    username: String,
    serverLabel: String,
    snackbar: SnackbarHostState,
    onOpenServers: () -> Unit,
    loginsRemembered: Boolean,
    onOpenLogins: () -> Unit,
    pageOpen: Boolean,
    onOpenAppearance: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenSecurity: () -> Unit,
) {
    val version by viewModel.version.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val openAppsIn by viewModel.openAppsIn.collectAsStateWithLifecycle()
    val lockEnabled by viewModel.lockEnabled.collectAsStateWithLifecycle()
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val showInFiles by viewModel.showInFiles.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<PowerAction?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    // The Appearance, Notifications and Security & privacy pages cover this one; the last two show messages themselves while open.
    if (!pageOpen) LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Column(
        Modifier.verticalScroll(rememberScrollState()).readableWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Settings")

        // Who's signed in where; tapping it switches server.
        ListGroup(Modifier.padding(top = 4.dp)) {
            ListRow(
                username.ifBlank { "Signed in" },
                subtitle = listOfNotNull(serverLabel, version?.let { "CasaOS $it" }).joinToString(" · "),
                leading = {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            username.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                },
                trailing = { Chevron() },
                onClick = onOpenServers,
            )
        }

        Section("Appearance") {
            ListGroup {
                ListRow(
                    "Appearance",
                    subtitle = theme.summary,
                    icon = Icons.Rounded.Palette,
                    trailing = { Chevron() },
                    onClick = onOpenAppearance,
                )
            }
        }

        WidgetsSection(viewModel)

        Section("Apps") {
            OpenAppsInSection(openAppsIn, viewModel::setOpenAppsIn)
            ListGroup {
                ListRow(
                    "Saved logins",
                    subtitle = if (loginsRemembered) "Apps stay signed in" else "Off",
                    icon = Icons.Rounded.Key,
                    trailing = { Chevron() },
                    onClick = onOpenLogins,
                )
            }
        }

        Section("Files") {
            ListGroup {
                SwitchRow(
                    "Show in Android Files",
                    checked = showInFiles && !lockEnabled,
                    onCheckedChange = viewModel::setShowInFiles,
                    subtitle = if (lockEnabled) "Not available while Lock Hearth is on" else "Browse your servers in the Files app",
                    icon = Icons.Rounded.FolderOpen,
                    enabled = !lockEnabled,
                )
            }
        }

        Section("Notifications") {
            ListGroup {
                ListRow(
                    "Notifications",
                    subtitle = if (alerts.enabled) "Alerts are on" else "Off",
                    icon = Icons.Rounded.Notifications,
                    trailing = { Chevron() },
                    onClick = onOpenNotifications,
                )
            }
        }

        Section("Security") {
            ListGroup {
                ListRow(
                    "Security & privacy",
                    icon = Icons.Rounded.Shield,
                    trailing = { Chevron() },
                    onClick = onOpenSecurity,
                )
            }
        }

        Section("Server") {
            ListGroup {
                ListRow("Restart server", icon = Icons.Rounded.RestartAlt, onClick = { confirm = PowerAction.Restart })
                ListRow(
                    "Shut down",
                    icon = Icons.Rounded.PowerSettingsNew,
                    iconTint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirm = PowerAction.Shutdown },
                )
            }
        }

        Section("Account") {
            ListGroup {
                ListRow("Switch server", icon = Icons.Rounded.SwapHoriz, trailing = { Chevron() }, onClick = onOpenServers)
                ListRow("Sign out", icon = Icons.AutoMirrored.Rounded.Logout, onClick = { confirmSignOut = true })
            }
        }

        Text(
            "Hearth ${BuildConfig.VERSION_NAME} · unofficial CasaOS client",
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }

    if (confirmSignOut) {
        SignOutDialog(serverLabel, onConfirm = { confirmSignOut = false; viewModel.signOut() }, onDismiss = { confirmSignOut = false })
    }

    confirm?.let { action ->
        val restart = action == PowerAction.Restart
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (restart) "Restart server?" else "Shut down server?") },
            text = {
                Text(
                    if (restart) "Your apps will be unavailable for a minute or two while it reboots."
                    else "Everything stops, and the server stays off until you power it on by hand.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.power(action); confirm = null }) {
                    Text(if (restart) "Restart" else "Shut down", color = if (restart) Color.Unspecified else MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** Signing out also removes logins that signing in again won't bring back, so it asks first. */
@Composable
private fun SignOutDialog(serverLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out of $serverLabel?") },
        text = {
            Text(
                "The server stays in your list. Hearth forgets its saved terminal password and SSH key, and signs you out of " +
                    "every app opened in Hearth, on all your servers.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Sign out", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The home screen widgets, each opening its own settings. Hidden while none is placed. */
@Composable
private fun WidgetsSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val options by viewModel.widgetOptions.collectAsStateWithLifecycle()
    // Checked again on return, in case a widget was just added or removed.
    var placed by remember { mutableStateOf(WidgetConfigActivity.placed(context)) }
    LifecycleResumeEffect(Unit) {
        placed = WidgetConfigActivity.placed(context)
        onPauseOrDispose {}
    }
    if (placed.isEmpty()) return
    Section("Home screen widgets") {
        ListGroup {
            placed.forEachIndexed { i, id ->
                ListRow(
                    if (placed.size == 1) "Server stats" else "Server stats ${i + 1}",
                    subtitle = (options[id] ?: WidgetOptions()).summary,
                    icon = Icons.Rounded.Dashboard,
                    trailing = { Chevron() },
                    onClick = { context.startActivity(WidgetConfigActivity.intent(context, id)) },
                )
            }
        }
    }
}

/** A titled group of settings. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        content()
    }
}
