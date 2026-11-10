package dev.hearth.casaos.ui.apps

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
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
import dev.hearth.casaos.data.versionLabel
import dev.hearth.casaos.data.webUrl
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import dev.hearth.casaos.ui.components.AppIcon
import dev.hearth.casaos.ui.components.CardRadius
import dev.hearth.casaos.ui.components.Chevron
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.GroupGap
import dev.hearth.casaos.ui.components.HeaderSubtitle
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.ListGroup
import dev.hearth.casaos.ui.components.ListRow
import dev.hearth.casaos.ui.components.ScreenHeader
import dev.hearth.casaos.ui.components.SearchField
import dev.hearth.casaos.ui.components.StatusDot
import dev.hearth.casaos.ui.components.adaptiveColumns
import dev.hearth.casaos.ui.components.groupShape
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

    /** Removes the app; [deleteData] also deletes its folder under /DATA/AppData. Calls [onDone] once it's gone. */
    fun uninstall(app: InstalledApp, deleteData: Boolean, onDone: () -> Unit) {
        if (app.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + app.id) }
        viewModelScope.launch {
            try {
                client.uninstallApp(app.id, deleteData)
                // Drop it at once: the next refresh may still list it while CasaOS finishes removing the containers.
                _state.update { s -> s.copy(apps = s.apps?.filterNot { it.id == app.id }) }
                onDone()
                _messages.emit("Uninstalled ${app.name}")
            } catch (e: CasaException) {
                _messages.emit("Couldn't uninstall ${app.name}: ${e.message}")
            } finally {
                _state.update { it.copy(busy = it.busy - app.id) }
            }
        }
    }
}

/** The narrowest an app card in the Apps and App Store grids gets before the grid drops a column. */
internal val AppCardMinWidth = 340.dp

/**
 * The Apps and App Store list: one grouped column on a phone, separate cards side by side on wider
 * screens. [content] gets the number of columns so rows can pick their shape.
 */
@Composable
internal fun AppGrid(modifier: Modifier = Modifier, content: LazyGridScope.(columns: Int) -> Unit) {
    BoxWithConstraints(modifier) {
        val side = 20.dp
        val gap = 12.dp
        val columns = adaptiveColumns(maxWidth - side * 2, AppCardMinWidth, gap)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (columns == 1) GroupGap else gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) { content(columns) }
    }
}

private enum class Filter(val label: String) { All("All"), Running("Running"), Stopped("Stopped") }

/** True if [q] appears in the app's name, id, tagline or image, ignoring case. */
internal fun InstalledApp.matches(q: String): Boolean =
    name.contains(q, true) || id.contains(q, true) || tagline?.contains(q, true) == true || image?.contains(q, true) == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    viewModel: AppsViewModel,
    serverBaseUrl: String,
    snackbar: SnackbarHostState,
    openAppsIn: OpenAppsIn,
    /** True while an app's settings or version page is open on top; the app's sheet steps aside until it closes. */
    pageOpen: Boolean,
    onOpenSettings: (InstalledApp) -> Unit,
    onOpenVersion: (InstalledApp) -> Unit,
    onOpenLogs: (InstalledApp) -> Unit,
    onOpenStore: () -> Unit,
    onOpenInHearth: (app: InstalledApp, url: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var uninstallId by rememberSaveable { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val createApp = rememberCreateApp()

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    // Back clears a search before it leaves the screen.
    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    val apps = state.apps
    when {
        apps == null && state.error == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        apps == null -> EmptyState("Couldn't load apps", state.error.orEmpty(), icon = Icons.Rounded.CloudOff)
        apps.isEmpty() -> EmptyState("No apps installed", "Install apps from the App Store and they'll show up here.", icon = Icons.Rounded.Apps) {
            Button(onClick = onOpenStore) {
                Icon(Icons.Rounded.Storefront, null, Modifier.size(18.dp))
                Text("Browse the App Store", Modifier.padding(start = 8.dp))
            }
        }
        else -> {
            val q = query.trim()
            val shown = remember(apps, filter, q) {
                apps.filter {
                    val inFilter = when (filter) {
                        Filter.All -> true
                        Filter.Running -> it.state == AppState.Running
                        Filter.Stopped -> it.state == AppState.Stopped
                    }
                    inFilter && (q.isEmpty() || it.matches(q))
                }
            }
            val running = remember(apps) { apps.count { it.state == AppState.Running } }
            // One column on a phone, as one grouped list; wider screens fit two or three app cards side by side.
            AppGrid { columns ->
                item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    ScreenHeader("Apps", subtitle = { HeaderSubtitle("$running of ${apps.size} running") }) {
                        FilledTonalButton(onClick = onOpenStore) {
                            Icon(Icons.Rounded.Storefront, null, Modifier.size(18.dp))
                            Text("App Store", Modifier.padding(start = 8.dp))
                        }
                    }
                }
                // The list on screen is the last one that loaded; say so rather than let it pass for current.
                state.error?.let { error ->
                    item(key = "error", span = { GridItemSpan(maxLineSpan) }, contentType = "error") {
                        ListRow(
                            "Can't reach your server",
                            Modifier.padding(top = 8.dp),
                            subtitle = "$error · showing the last list",
                            icon = Icons.Rounded.CloudOff,
                            iconTint = MaterialTheme.colorScheme.error,
                            shape = RoundedCornerShape(CardRadius),
                        )
                    }
                }
                item(key = "search", span = { GridItemSpan(maxLineSpan) }, contentType = "search") {
                    SearchField(query, { query = it }, "Search ${apps.size} apps", Modifier.padding(top = 8.dp))
                }
                item(key = "filters", span = { GridItemSpan(maxLineSpan) }, contentType = "filters") {
                    Row(Modifier.padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Filter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                    }
                }
                itemsIndexed(shown, key = { _, it -> it.id }, contentType = { _, _ -> "app" }) { i, app ->
                    val webUrl = app.webUrl(serverBaseUrl)
                    AppRow(
                        app = app,
                        shape = if (columns == 1) groupShape(i, shown.size) else RoundedCornerShape(CardRadius),
                        busy = app.id in state.busy,
                        onOpen = { selectedId = app.id },
                        onToggle = {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            viewModel.perform(app, if (app.state == AppState.Running) AppAction.Stop else AppAction.Start)
                        },
                        // Only apps with a web UI can become a home screen app.
                        onCreateApp = if (webUrl != null) { { createApp(app.name, webUrl, app.iconUrl) } } else null,
                        onUninstall = { uninstallId = app.id },
                    )
                }
                if (shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            when {
                                q.isEmpty() -> "Nothing here."
                                filter == Filter.All -> "No apps match \"$q\"."
                                else -> "No ${filter.label.lowercase()} apps match \"$q\"."
                            },
                            Modifier.padding(top = 24.dp).fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    val selected = apps?.firstOrNull { it.id == selectedId }
    // The sheet is its own window and would cover the page opened from it; it comes back when the page closes.
    if (selected != null && !pageOpen) {
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
            onOpenSettings = { onOpenSettings(selected) },
            onOpenVersion = { onOpenVersion(selected) },
            onUninstall = { uninstallId = selected.id },
            onOpenLogs = { onOpenLogs(selected) },
        )
    }

    val toUninstall = apps?.firstOrNull { it.id == uninstallId }
    if (toUninstall != null) {
        UninstallDialog(
            app = toUninstall,
            busy = toUninstall.id in state.busy,
            onDismiss = { uninstallId = null },
            onConfirm = { deleteData ->
                viewModel.uninstall(toUninstall, deleteData) {
                    uninstallId = null
                    if (selectedId == toUninstall.id) selectedId = null
                }
            },
        )
    }
}

@Composable
private fun UninstallDialog(app: InstalledApp, busy: Boolean, onDismiss: () -> Unit, onConfirm: (deleteData: Boolean) -> Unit) {
    var deleteData by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { AppIcon(app.iconUrl, app.name, size = 48.dp) },
        title = { Text("Uninstall ${app.name}?") },
        text = {
            Column {
                Text("The app and its containers are removed from the server.")
                Row(
                    Modifier.padding(top = 12.dp).clickable(enabled = !busy) { deleteData = !deleteData },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = deleteData, onCheckedChange = { deleteData = it }, enabled = !busy)
                    Column {
                        Text("Also delete its data", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Settings and files it keeps in /DATA/AppData/${app.id}. Leave unticked to keep them for a reinstall.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(22.dp), strokeWidth = 2.5.dp)
            } else {
                TextButton(onClick = { onConfirm(deleteData) }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** Tapping opens the app's details; long-pressing offers "Create app" (apps with a web UI) and "Uninstall". */
@Composable
private fun AppRow(
    app: InstalledApp,
    shape: Shape,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onCreateApp: (() -> Unit)?,
    onUninstall: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        AppCard(app, shape, busy, onOpen, onToggle, onLongClick = { menu = true })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(14.dp, 0.dp)) {
            if (onCreateApp != null) {
                DropdownMenuItem(
                    text = { Text("Create app") },
                    leadingIcon = { Icon(Icons.Rounded.InstallMobile, null) },
                    onClick = {
                        menu = false
                        onCreateApp()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Uninstall", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                enabled = !busy,
                onClick = {
                    menu = false
                    onUninstall()
                },
            )
        }
    }
}

@Composable
private fun AppCard(app: InstalledApp, shape: Shape, busy: Boolean, onOpen: () -> Unit, onToggle: () -> Unit, onLongClick: (() -> Unit)?) {
    // Stopped apps are greyed out so they stand apart from running ones; the Start button keeps full colour.
    val stopped = app.state == AppState.Stopped
    HearthCard(Modifier.fillMaxWidth(), onClick = onOpen, onLongClick = onLongClick, shape = shape) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.iconUrl, app.name, Modifier.alpha(if (stopped) 0.45f else 1f), size = 48.dp, grayscale = stopped)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp).alpha(if (stopped) 0.6f else 1f)) {
                Text(
                    app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (stopped) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                )
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
                    // Stop is quiet; Start, the way back for a stopped app, carries the accent.
                    FilledTonalIconButton(
                        onClick = onToggle,
                        colors = if (running) {
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            IconButtonDefaults.filledTonalIconButtonColors()
                        },
                    ) {
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
    onOpenSettings: () -> Unit,
    onOpenVersion: () -> Unit,
    onUninstall: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val webUrl = app.webUrl(serverBaseUrl)

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
                FilledTonalButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); onAction(if (running) AppAction.Stop else AppAction.Start) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    }
                    Text(if (running) "Stop" else "Start", Modifier.padding(start = 8.dp), maxLines = 1)
                }
                FilledTonalButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); onAction(AppAction.Restart) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                    Text("Restart", Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }

            ListGroup(Modifier.padding(top = 8.dp)) {
                val update = app.updateAvailable
                ListRow(
                    if (update) "Update available" else "Version",
                    subtitle = app.image?.let(::versionLabel) ?: "Choose a version",
                    icon = if (update) Icons.Rounded.SystemUpdateAlt else Icons.Rounded.Sell,
                    trailing = {
                        if (update) {
                            Badge("Update")
                        } else {
                            Chevron()
                        }
                    },
                    onClick = onOpenVersion,
                )
                ListRow("Logs", icon = Icons.AutoMirrored.Rounded.Article, trailing = { Chevron() }, onClick = onOpenLogs)
                ListRow(
                    "Settings",
                    subtitle = "Ports, folders and environment",
                    icon = Icons.Rounded.Tune,
                    trailing = { Chevron() },
                    onClick = onOpenSettings,
                )
                if (webUrl != null) ListRow("Web address", subtitle = webUrl, icon = Icons.Rounded.Link)
            }
            ListGroup {
                ListRow(
                    "Uninstall",
                    icon = Icons.Rounded.Delete,
                    iconTint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    enabled = !busy,
                    onClick = onUninstall,
                )
            }
        }
    }
}

/** A small accent pill at the end of a row, e.g. "Update". */
@Composable
private fun Badge(text: String) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onPrimary,
    )
}
