package dev.hearth.casaos.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.StoreApp
import dev.hearth.casaos.ui.components.AppIcon
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.SearchField
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StoreState(
    val apps: List<StoreApp>? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** Store ids being sent to the server right now. */
    val installing: Set<String> = emptySet(),
    /** Store ids installed from this page; the store list only learns about them on the next load. */
    val installedHere: Set<String> = emptySet(),
) {
    fun isInstalled(app: StoreApp) = app.installed || app.id in installedHere
}

class StoreViewModel(private val client: CasaClient) : ViewModel() {
    private val _state = MutableStateFlow(StoreState())
    val state: StateFlow<StoreState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val apps = client.storeApps()
                _state.update { it.copy(apps = apps, loading = false) }
            } catch (e: CasaException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun install(app: StoreApp) {
        if (app.id in _state.value.installing) return
        _state.update { it.copy(installing = it.installing + app.id) }
        viewModelScope.launch {
            try {
                client.installStoreApp(app.id)
                _state.update { it.copy(installedHere = it.installedHere + app.id) }
                _messages.emit("Installing ${app.title}. It shows up in Apps when it's ready, which can take a few minutes.")
            } catch (e: CasaException) {
                _messages.emit("Couldn't install ${app.title}: ${e.message}")
            } finally {
                _state.update { it.copy(installing = it.installing - app.id) }
            }
        }
    }
}

/** The CasaOS App Store: browse, search and install apps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(viewModel: StoreViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(onBack = onClose)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") } },
                title = { Text("App Store") },
                actions = {
                    IconButton(onClick = viewModel::load, enabled = !state.loading) { Icon(Icons.Rounded.Refresh, "Reload") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            val apps = state.apps
            when {
                apps == null && state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                apps == null -> EmptyState("Couldn't load the App Store", state.error.orEmpty()) {
                    TextButton(onClick = viewModel::load) { Text("Try again") }
                }
                else -> {
                    val categories = remember(apps) { apps.mapNotNull { it.category }.distinct().sorted() }
                    val shown = remember(apps, query, category) {
                        val q = query.trim()
                        apps.filter { a ->
                            (category == null || a.category == category) &&
                                (q.isEmpty() || a.title.contains(q, true) || a.tagline?.contains(q, true) == true || a.developer?.contains(q, true) == true)
                        }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "search", contentType = "search") {
                            SearchField(query, { query = it }, "Search ${apps.size} apps")
                        }
                        if (categories.size > 1) {
                            item(key = "categories", contentType = "categories") {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") })
                                    categories.forEach { c ->
                                        FilterChip(selected = category == c, onClick = { category = if (category == c) null else c }, label = { Text(c) })
                                    }
                                }
                            }
                        }
                        items(shown, key = { it.id }, contentType = { "app" }) { app ->
                            StoreRow(
                                app,
                                installed = state.isInstalled(app),
                                installing = app.id in state.installing,
                                onOpen = { selectedId = app.id },
                                onInstall = { viewModel.install(app) },
                            )
                        }
                        if (shown.isEmpty()) {
                            item { Text("No apps match.", Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }

    val selected = state.apps?.firstOrNull { it.id == selectedId }
    if (selected != null) {
        StoreAppSheet(
            app = selected,
            installed = state.isInstalled(selected),
            installing = selected.id in state.installing,
            onInstall = { viewModel.install(selected) },
            onDismiss = { selectedId = null },
        )
    }
}

@Composable
private fun StoreRow(app: StoreApp, installed: Boolean, installing: Boolean, onOpen: () -> Unit, onInstall: () -> Unit) {
    HearthCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.iconUrl, app.title)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(app.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                (app.tagline ?: app.category)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            InstallButton(installed, installing, onInstall, compact = true)
        }
    }
}

@Composable
private fun InstallButton(installed: Boolean, installing: Boolean, onInstall: () -> Unit, compact: Boolean, modifier: Modifier = Modifier) {
    when {
        installing -> Box(modifier.size(48.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp) }
        installed -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Installed", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        compact -> TextButton(onClick = onInstall, modifier = modifier) { Text("Install") }
        else -> Button(onClick = onInstall, modifier = modifier) {
            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
            Text("Install", Modifier.padding(start = 8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreAppSheet(app: StoreApp, installed: Boolean, installing: Boolean, onInstall: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.iconUrl, app.title, size = 64.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(app.title, style = MaterialTheme.typography.headlineSmall)
                    val meta = listOfNotNull(app.developer, app.category).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            app.tagline?.let {
                Text(it, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InstallButton(installed, installing, onInstall, compact = false, modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(52.dp))
            if (app.screenshots.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(app.screenshots) { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "Screenshot of ${app.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.height(180.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp)),
                        )
                    }
                }
            }
            app.description?.let {
                Text(it, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
