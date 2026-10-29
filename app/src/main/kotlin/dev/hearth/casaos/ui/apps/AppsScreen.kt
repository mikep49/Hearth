package dev.hearth.casaos.ui.apps

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hearth.casaos.data.AppAction
import dev.hearth.casaos.data.AppState
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.InstalledApp
import dev.hearth.casaos.data.OpenAppsIn
import dev.hearth.casaos.data.webUrl
import dev.hearth.casaos.ui.components.AppIcon
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.StatusDot
import dev.hearth.casaos.ui.theme.hearth
import dev.hearth.casaos.ui.web.rememberCreateApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppsState(
    val apps: List<InstalledApp>? = null,
    val busy: Set<String> = emptySet(),
    val error: String? = null,
)

class AppsViewModel(private val client: CasaClient) : ViewModel() {
    private val _state = MutableStateFlow(AppsState())
    val state: StateFlow<AppsState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    suspend fun poll() {
        while (true) {
            refresh()
            delay(8_000)
        }
    }

    suspend fun refresh() {
        try {
            val apps = client.apps()
            _state.update { it.copy(apps = apps, error = null) }
        } catch (e: CasaException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun perform(app: InstalledApp, action: AppAction) {
        if (app.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + app.id) }
        viewModelScope.launch {
            try {
                client.setAppStatus(app.id, action)
                refresh()
            } catch (e: CasaException) {
                _messages.emit("Couldn't ${action.wire} ${app.name}: ${e.message}")
            } finally {
                _state.update { it.copy(busy = it.busy - app.id) }
            }
        }
    }

    suspend fun logs(id: String): Result<String> =
        try { Result.success(client.appLogs(id)) } catch (e: CasaException) { Result.failure(e) }
}

private enum class Filter(val label: String) { All("All"), Running("Running"), Stopped("Stopped") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    viewModel: AppsViewModel,
    serverBaseUrl: String,
    snackbar: SnackbarHostState,
    openAppsIn: OpenAppsIn,
    onOpenInHearth: (app: InstalledApp, url: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val createApp = rememberCreateApp()

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val apps = state.apps
    when {
        apps == null && state.error == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        apps == null -> EmptyState("Couldn't load apps", state.error.orEmpty())
        apps.isEmpty() -> EmptyState("No apps installed", "Install apps from the CasaOS App Store and they'll show up here.")
        else -> {
            val shown = remember(apps, filter) {
                apps.filter {
                    when (filter) {
                        Filter.All -> true
                        Filter.Running -> it.state == AppState.Running
                        Filter.Stopped -> it.state == AppState.Stopped
                    }
                }
            }
            val running = remember(apps) { apps.count { it.state == AppState.Running } }
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header", contentType = "header") {
                    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                        Text("Apps", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "$running of ${apps.size} running",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = "filters", contentType = "filters") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Filter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                    }
                }
                items(shown, key = { it.id }, contentType = { "app" }) { app ->
                    val webUrl = app.webUrl(serverBaseUrl)
                    AppRow(
                        app = app,
                        busy = app.id in state.busy,
                        onOpen = { selectedId = app.id },
                        onToggle = {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            viewModel.perform(app, if (app.state == AppState.Running) AppAction.Stop else AppAction.Start)
                        },
                        // Only apps with a web UI can become a home screen app.
                        onCreateApp = if (webUrl != null) { { createApp(app.name, webUrl, app.iconUrl) } } else null,
                    )
                }
                if (shown.isEmpty()) {
                    item { Text("Nothing here.", Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }

    val selected = apps?.firstOrNull { it.id == selectedId }
    if (selected != null) {
        AppSheet(
            app = selected,
            busy = selected.id in state.busy,
            serverBaseUrl = serverBaseUrl,
            openAppsIn = openAppsIn,
            onOpenInHearth = { url ->
                // The sheet is its own window and would sit on top of the in-app browser.
                selectedId = null
                onOpenInHearth(selected, url)
            },
            onDismiss = { selectedId = null },
            onAction = { viewModel.perform(selected, it) },
            loadLogs = { viewModel.logs(selected.id) },
        )
    }
}

/** Tapping opens the app's details; long-pressing an app with a web UI offers "Create app". */
@Composable
private fun AppRow(app: InstalledApp, busy: Boolean, onOpen: () -> Unit, onToggle: () -> Unit, onCreateApp: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Box {
        AppCard(app, busy, onOpen, onToggle, onLongClick = if (onCreateApp != null) { { menu = true } } else null)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(14.dp, 0.dp)) {
            DropdownMenuItem(
                text = { Text("Create app") },
                leadingIcon = { Icon(Icons.Rounded.InstallMobile, null) },
                onClick = {
                    menu = false
                    onCreateApp?.invoke()
                },
            )
        }
    }
}

@Composable
private fun AppCard(app: InstalledApp, busy: Boolean, onOpen: () -> Unit, onToggle: () -> Unit, onLongClick: (() -> Unit)?) {
    HearthCard(Modifier.fillMaxWidth(), onClick = onOpen, onLongClick = onLongClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.iconUrl, app.name)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateLabel(app.state, app.rawStatus)
                    if (app.updateAvailable) {
                        Icon(
                            Icons.Rounded.SystemUpdateAlt, "Update available",
                            Modifier.padding(start = 8.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Box(Modifier.size(48.dp), Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else if (app.state != AppState.Restarting) {
                    val running = app.state == AppState.Running
                    FilledTonalIconButton(onClick = onToggle) {
                        Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (running) "Stop ${app.name}" else "Start ${app.name}")
                    }
                }
            }
        }
    }
}

@Composable
private fun StateLabel(state: AppState, raw: String) {
    val c = MaterialTheme.hearth
    val (color, text) = when (state) {
        AppState.Running -> c.good to "Running"
        AppState.Stopped -> MaterialTheme.colorScheme.outline to "Stopped"
        AppState.Restarting -> c.warn to "Restarting"
        AppState.Unknown -> MaterialTheme.colorScheme.outline to raw.replaceFirstChar { it.uppercase() }.ifBlank { "Unknown" }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, pulse = state == AppState.Running || state == AppState.Restarting)
        Text(text, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(
    app: InstalledApp,
    busy: Boolean,
    serverBaseUrl: String,
    openAppsIn: OpenAppsIn,
    onOpenInHearth: (url: String) -> Unit,
    onDismiss: () -> Unit,
    onAction: (AppAction) -> Unit,
    loadLogs: suspend () -> Result<String>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var logs by remember { mutableStateOf<Result<String>?>(null) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var loadingLogs by remember { mutableStateOf(false) }
    val webUrl = app.webUrl(serverBaseUrl)

    fun fetch() {
        loadingLogs = true
        scope.launch { logs = loadLogs(); loadingLogs = false }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.iconUrl, app.name, size = 64.dp)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(app.name, style = MaterialTheme.typography.headlineSmall)
                    StateLabel(app.state, app.rawStatus)
                }
            }
            app.tagline?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (webUrl != null) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        when (openAppsIn) {
                            OpenAppsIn.Hearth -> onOpenInHearth(webUrl)
                            OpenAppsIn.Browser -> context.startActivity(
                                Intent(Intent.ACTION_VIEW, webUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    enabled = app.state == AppState.Running,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    val icon = if (openAppsIn == OpenAppsIn.Hearth) Icons.Rounded.Public else Icons.AutoMirrored.Rounded.OpenInNew
                    Icon(icon, null, Modifier.size(18.dp))
                    Text("Open", Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val running = app.state == AppState.Running
                OutlinedButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); onAction(if (running) AppAction.Stop else AppAction.Start) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Text(if (running) "Stop" else "Start", Modifier.padding(start = 8.dp), maxLines = 1)
                }
                OutlinedButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); onAction(AppAction.Restart) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                    Text("Restart", Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Working…", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (webUrl != null) {
                Text(webUrl, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showLogs = !showLogs; if (showLogs && logs == null) fetch() }) {
                    Icon(Icons.AutoMirrored.Rounded.Article, null, Modifier.size(18.dp))
                    Text(if (showLogs) "Hide logs" else "View logs", Modifier.padding(start = 8.dp))
                }
                if (showLogs) {
                    IconButton(onClick = ::fetch, enabled = !loadingLogs) { Icon(Icons.Rounded.Refresh, "Refresh logs") }
                }
            }
            if (showLogs) LogView(logs, loadingLogs)
        }
    }
}

@Composable
private fun LogView(result: Result<String>?, loading: Boolean) {
    val scroll = rememberScrollState()
    val text = result?.getOrNull()
    // Newest lines are at the bottom: start there.
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 320.dp)
            .background(Color(0xFF07080B), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        when {
            loading && text == null -> CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.Center), strokeWidth = 2.dp)
            result?.isFailure == true -> Text(result.exceptionOrNull()?.message.orEmpty(), color = MaterialTheme.colorScheme.error)
            text.isNullOrBlank() -> Text("No log output.", color = Color(0xFF8A90A0))
            else -> Text(
                text,
                modifier = Modifier.verticalScroll(scroll).horizontalScroll(rememberScrollState()),
                softWrap = false,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Color(0xFFC9D1D9),
            )
        }
    }
}
