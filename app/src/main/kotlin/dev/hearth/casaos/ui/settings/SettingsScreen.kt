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
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
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
import dev.hearth.casaos.ui.theme.ThemeSettings
import dev.hearth.casaos.ui.theme.ThemeStore
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
) : ViewModel() {
    private val _version = MutableStateFlow<String?>(null)
    val version: StateFlow<String?> = _version.asStateFlow()

    val theme: StateFlow<ThemeSettings> = themeStore.settings

    fun setTheme(settings: ThemeSettings) = themeStore.update(settings)

    val openAppsIn: StateFlow<OpenAppsIn> = prefs.openAppsIn

    fun setOpenAppsIn(value: OpenAppsIn) = prefs.setOpenAppsIn(value)

    val alerts: StateFlow<AlertSettings> = prefs.alerts

    fun setAlerts(value: AlertSettings) = prefs.setAlerts(value)

    val lockEnabled: StateFlow<Boolean> = lock.enabled
    val lockAfter: StateFlow<LockAfter> = lock.lockAfter

    fun lockAvailable(): Boolean = lock.available()

    fun setLockEnabled(value: Boolean) = lock.setEnabled(value)

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
) {
    val version by viewModel.version.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val openAppsIn by viewModel.openAppsIn.collectAsStateWithLifecycle()
    val lockEnabled by viewModel.lockEnabled.collectAsStateWithLifecycle()
    val lockAfter by viewModel.lockAfter.collectAsStateWithLifecycle()
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<PowerAction?>(null) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

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

        Section("Appearance") { AppearanceSection(theme, viewModel::setTheme) }

        Section("Apps") {
            OpenAppsInSection(openAppsIn, viewModel::setOpenAppsIn)
            ListGroup {
                ListRow(
                    "Saved logins",
                    subtitle = if (loginsRemembered) "Apps opened in Hearth that keep you signed in" else "Off: apps sign you out when you close them",
                    icon = Icons.Rounded.Key,
                    trailing = { Chevron() },
                    onClick = onOpenLogins,
                )
            }
        }

        Section("Alerts") { AlertsSection(alerts, viewModel::setAlerts, onError = viewModel::showError) }

        Section("Security") {
            SecuritySection(
                lockEnabled,
                lockAfter,
                available = viewModel::lockAvailable,
                onEnabledChange = viewModel::setLockEnabled,
                onLockAfterChange = viewModel::setLockAfter,
                onError = viewModel::showError,
            )
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
                ListRow("Sign out", icon = Icons.AutoMirrored.Rounded.Logout, onClick = viewModel::signOut)
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

/** A titled group of settings. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        content()
    }
}
