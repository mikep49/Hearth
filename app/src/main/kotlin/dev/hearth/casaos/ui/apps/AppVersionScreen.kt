package dev.hearth.casaos.ui.apps

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hearth.casaos.data.AppSettings
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.ImageRef
import dev.hearth.casaos.data.ImageRegistry
import dev.hearth.casaos.data.ImageTag
import dev.hearth.casaos.data.ServiceSettings
import dev.hearth.casaos.data.TagPage
import dev.hearth.casaos.data.applyAppSettings
import dev.hearth.casaos.data.compareVersions
import dev.hearth.casaos.data.currentTag
import dev.hearth.casaos.data.isNoiseTag
import dev.hearth.casaos.data.isVersionTag
import dev.hearth.casaos.data.joinImage
import dev.hearth.casaos.data.parseAppSettings
import dev.hearth.casaos.data.parseImageRef
import dev.hearth.casaos.data.parseStoreAppId
import dev.hearth.casaos.data.validate
import dev.hearth.casaos.data.versionLabel
import dev.hearth.casaos.data.withImageTag
import dev.hearth.casaos.ui.components.ReadableGutter
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An image's tags as last fetched, for one search on Docker Hub (other registries return them all at once). */
@Immutable
data class TagList(
    val image: ImageRef,
    val search: String = "",
    val loading: Boolean = true,
    val page: TagPage? = null,
    val error: String? = null,
    /** What the current tag is right now on Docker Hub, to say what a moving tag like `latest` points at. */
    val current: ImageTag? = null,
)

data class AppVersionUi(
    val loading: Boolean = false,
    val loadError: String? = null,
    val yaml: String? = null,
    val settings: AppSettings? = null,
    val serviceIndex: Int = 0,
    /** The version the CasaOS App Store ships for the main container; null for apps not from the store. */
    val storeTag: String? = null,
    val tags: TagList? = null,
    val query: String = "",
    val showAll: Boolean = false,
    val applying: Boolean = false,
    val error: String? = null,
    /** An App Store update was started from here, so the button goes away until the app list catches up. */
    val updateStarted: Boolean = false,
) {
    val service: ServiceSettings? get() = settings?.services?.getOrNull(serviceIndex)
}

/**
 * Which version (image tag) each of an app's containers runs, and switching it, like changing the tag in the
 * CasaOS dashboard's settings. Tags come from the image's registry; switching writes the new tag into the
 * app's compose file, and CasaOS downloads it and recreates the container.
 */
class AppVersionViewModel(
    private val client: CasaClient,
    private val registry: ImageRegistry,
    private val appId: String,
) : ViewModel() {
    var ui by mutableStateOf(AppVersionUi())
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var tagsJob: Job? = null

    fun open() {
        if (ui.settings == null && !ui.loading) load()
    }

    fun load() {
        ui = ui.copy(loading = true, loadError = null)
        viewModelScope.launch {
            try {
                val yaml = client.appComposeYaml(appId)
                val (settings, storeId) = withContext(Dispatchers.Default) { parseAppSettings(yaml) to parseStoreAppId(yaml) }
                val index = ui.serviceIndex.coerceIn(0, settings.services.lastIndex)
                ui = ui.copy(loading = false, yaml = yaml, settings = settings, serviceIndex = index, error = null)
                loadTags(force = true)
                launch {
                    // Store apps only. It's about the main container, which parseAppSettings puts first.
                    val tag = client.storeStableTag(storeId ?: appId)
                    ui = ui.copy(storeTag = tag)
                }
            } catch (e: CasaException) {
                ui = ui.copy(loading = false, loadError = e.message)
                if (ui.settings != null) _messages.emit("Couldn't reload: ${e.message}")
            }
        }
    }

    fun selectService(index: Int) {
        if (index == ui.serviceIndex || ui.applying) return
        ui = ui.copy(serviceIndex = index, query = "", showAll = false, error = null)
        loadTags(force = true)
    }

    fun search(query: String) {
        ui = ui.copy(query = query)
        // Docker Hub searches on the server, so older versions beyond the newest few hundred can be found.
        if (ui.tags?.image?.isDockerHub == true) loadTags(debounce = true)
    }

    fun toggleShowAll() {
        ui = ui.copy(showAll = !ui.showAll)
    }

    fun retryTags() = loadTags(force = true)

    private fun loadTags(force: Boolean = false, debounce: Boolean = false) {
        val service = ui.service ?: return
        val image = parseImageRef(service.image)
        if (image == null) {
            tagsJob?.cancel()
            ui = ui.copy(tags = null)
            return
        }
        val search = if (image.isDockerHub) ui.query.trim() else ""
        val existing = ui.tags
        if (!force && existing != null && existing.image == image && existing.search == search && existing.error == null) return
        tagsJob?.cancel()
        ui = ui.copy(
            tags = TagList(
                image = image,
                search = search,
                // Keep showing the old list while a search loads, so the screen doesn't flash empty.
                page = existing?.page?.takeIf { existing.image == image },
                current = existing?.current?.takeIf { existing.image == image },
            ),
        )
        tagsJob = viewModelScope.launch {
            if (debounce) delay(400)
            val current = currentTag(joinImage(service.image, service.tag))
            try {
                val page = registry.tags(image, search)
                val pointsAt = if (search.isEmpty() && current != null && !isVersionTag(current)) registry.tag(image, current) else ui.tags?.current
                ui = ui.copy(tags = TagList(image, search, loading = false, page = page, current = pointsAt))
            } catch (e: CasaException) {
                ui = ui.copy(tags = ui.tags?.copy(loading = false, error = e.message))
            }
        }
    }

    /** Switches the selected container to [tag]: writes it into the compose file, and CasaOS takes it from there. */
    fun switchTo(tag: String) {
        val state = ui
        val yaml = state.yaml ?: return
        val settings = state.settings ?: return
        if (state.applying) return
        val newSettings = settings.withImageTag(state.serviceIndex, tag.trim())
        // Only what the new tag breaks: anything else was already accepted by CasaOS and isn't changing.
        val problems = newSettings.validate() - settings.validate().toSet()
        if (problems.isNotEmpty()) {
            ui = state.copy(error = problems.first())
            return
        }
        val newYaml = try {
            applyAppSettings(yaml, settings, newSettings)
        } catch (e: CasaException) {
            ui = state.copy(error = e.message)
            return
        }
        ui = state.copy(applying = true, error = null)
        viewModelScope.launch {
            try {
                client.applyAppSettings(appId, newYaml)
                ui = ui.copy(applying = false, yaml = newYaml, settings = parseAppSettings(newYaml), query = "")
                loadTags(force = true)
                _messages.emit("Switching to ${tag.trim()}. CasaOS is downloading it and will restart the app.")
            } catch (e: CasaException) {
                ui = ui.copy(applying = false, error = e.message)
            }
        }
    }

    /** The dashboard's "Update": moves the app to the version the App Store now ships. */
    fun updateFromStore() {
        if (ui.applying) return
        ui = ui.copy(applying = true, error = null)
        viewModelScope.launch {
            try {
                client.updateApp(appId)
                ui = ui.copy(applying = false, updateStarted = true)
                _messages.emit("Updating. CasaOS is downloading the new version and will restart the app.")
            } catch (e: CasaException) {
                ui = ui.copy(applying = false, error = "Couldn't update: ${e.message}")
            }
        }
    }

    fun close() {
        if (!ui.applying) {
            tagsJob?.cancel()
            ui = AppVersionUi()
        }
    }
}

/** A tag in the list, with what's known about it. */
@Immutable
private data class TagRow(val tag: ImageTag, val current: Boolean, val store: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppVersionScreen(
    viewModel: AppVersionViewModel,
    appName: String,
    /** CasaOS's own "update available" flag for this app, from the app list. */
    updateAvailable: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = viewModel.ui
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<String?>(null) }
    var confirmUpdate by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    LaunchedEffect(viewModel) { viewModel.open() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    fun close() {
        if (ui.applying) return
        viewModel.close()
        onClose()
    }
    BackHandler { close() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::close, enabled = !ui.applying) { Icon(Icons.Rounded.Close, "Close") }
                },
                title = {
                    Column {
                        Text(appName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Version", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (ui.applying) {
                        CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(22.dp), strokeWidth = 2.5.dp)
                    } else if (ui.settings != null) {
                        IconButton(onClick = viewModel::load, enabled = !ui.loading) { Icon(Icons.Rounded.Refresh, "Reload") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            val settings = ui.settings
            val service = ui.service
            when {
                settings == null && ui.loadError == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                settings == null || service == null -> EmptyState("Couldn't load versions", ui.loadError.orEmpty()) {
                    TextButton(onClick = viewModel::load) { Text("Try again") }
                }
                else -> VersionList(
                    ui = ui,
                    settings = settings,
                    service = service,
                    showUpdate = updateAvailable && !ui.updateStarted && ui.serviceIndex == 0,
                    onSelectService = viewModel::selectService,
                    onSearch = viewModel::search,
                    onPick = { focus.clearFocus(); confirm = it },
                    onUpdate = { confirmUpdate = true },
                    onToggleShowAll = viewModel::toggleShowAll,
                    onRetry = viewModel::retryTags,
                )
            }
        }
    }

    confirm?.let { tag ->
        val service = ui.service
        val currentTag = service?.let { currentTag(joinImage(it.image, it.tag)) }
        val older = isOlder(tag, currentTag, ui.tags?.page?.tags.orEmpty())
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.Sell, null) },
            title = { Text("Switch to $tag?") },
            text = {
                Text(
                    buildString {
                        append("CasaOS will download ")
                        append(service?.let { joinImage(it.image.substringBefore('@'), tag) } ?: tag)
                        append(" and restart $appName with it. Its settings and data stay as they are.")
                        if (older) {
                            append("\n\nThis is an older version. Some apps upgrade their data and can't open it with an older version, so back up first.")
                        }
                    },
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; viewModel.switchTo(tag) }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    if (confirmUpdate) {
        AlertDialog(
            onDismissRequest = { confirmUpdate = false },
            icon = { Icon(Icons.Rounded.SystemUpdateAlt, null) },
            title = { Text("Update $appName?") },
            text = {
                Text(
                    "CasaOS will download the version its App Store now ships" +
                        (ui.storeTag?.let { " ($it)" } ?: "") +
                        " and restart the app with it. Its settings and data stay as they are.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmUpdate = false; viewModel.updateFromStore() }) { Text("Update") } },
            dismissButton = { TextButton(onClick = { confirmUpdate = false }) { Text("Cancel") } },
        )
    }
}

/** Whether [target] is known to be older than [current]: by push date on Docker Hub, else by version number. */
private fun isOlder(target: String, current: String?, tags: List<ImageTag>): Boolean {
    if (current == null || target == current) return false
    val t = tags.firstOrNull { it.name == target }?.updatedAt
    val c = tags.firstOrNull { it.name == current }?.updatedAt
    if (t != null && c != null) return t < c
    return isVersionTag(target) && isVersionTag(current) && compareVersions(target, current) < 0
}

@Composable
private fun VersionList(
    ui: AppVersionUi,
    settings: AppSettings,
    service: ServiceSettings,
    showUpdate: Boolean,
    onSelectService: (Int) -> Unit,
    onSearch: (String) -> Unit,
    onPick: (String) -> Unit,
    onUpdate: () -> Unit,
    onToggleShowAll: () -> Unit,
    onRetry: () -> Unit,
) {
    val fullImage = joinImage(service.image, service.tag)
    val current = currentTag(fullImage)
    val image = remember(service.image) { parseImageRef(service.image) }
    val storeTag = ui.storeTag.takeIf { ui.serviceIndex == 0 }
    val tags = ui.tags
    val query = ui.query.trim()
    val all = tags?.page?.tags.orEmpty()

    // Some registries list thousands of tags: filter once per change, not on every frame.
    val (rows, hidden) = remember(all, query, ui.showAll, current, storeTag) {
        val matching = if (query.isEmpty()) all else all.filter { it.name.contains(query, ignoreCase = true) }
        val visible = if (ui.showAll) matching else matching.filterNot { isNoiseTag(it.name) }
        visible.map { TagRow(it, current = it.name == current, store = it.name == storeTag) } to matching.size - visible.size
    }
    val anyNoise = remember(all) { all.any { isNoiseTag(it.name) } }
    // A typed tag the list doesn't have can still be used: when nothing matches it, or the registry can't be
    // read in full. Not while results are showing, where it would only get in the way of a search.
    val custom = query.takeIf { q ->
        q.isNotEmpty() && q != current && TagLike.matches(q) && rows.none { it.tag.name == q } &&
            (tags == null || tags.error != null || (!tags.loading && (rows.isEmpty() || tags.page?.truncated == true && !tags.image.isDockerHub)))
    }
    // What a moving tag like `latest` is right now: the version tags that are the same image.
    val sameAs = remember(tags?.current, all, current) {
        tags?.current?.digest?.let { d -> all.filter { it.digest == d && it.name != current && isVersionTag(it.name) }.map { it.name } }
    }
    val gap = Modifier.padding(bottom = 12.dp)
    val listState = rememberLazyListState()
    // The list keeps its place by item, so an error added above the tag that was tapped would stay off screen.
    LaunchedEffect(ui.error) { if (ui.error != null) listState.animateScrollToItem(0) }

    // No spacing between items: the tag rows join into one card. Other items add their own gap below.
    ReadableGutter(min = 16.dp) { side ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = side, vertical = 12.dp)) {
            if (ui.error != null) item(key = "error") { Box(gap) { Banner(ui.error, isError = true) } }

            if (settings.services.size > 1) {
                item(key = "containers") {
                    Row(gap.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        settings.services.forEachIndexed { i, s ->
                            FilterChip(selected = i == ui.serviceIndex, onClick = { onSelectService(i) }, label = { Text(s.name) }, enabled = !ui.applying)
                        }
                    }
                }
            }

            item(key = "current") {
                HearthCard(gap.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Running version", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(versionLabel(fullImage), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                        Text(
                            image?.let { if (it.isDockerHub) "${it.display} on Docker Hub" else it.display } ?: service.image,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!sameAs.isNullOrEmpty() && current != null) {
                            Hint("On Docker Hub, $current is now the same image as ${sameAs.take(3).joinToString(", ")}.")
                        }
                        if (storeTag != null) {
                            Hint(if (storeTag == current) "This is the version the CasaOS App Store ships." else "The CasaOS App Store ships $storeTag.")
                        }
                        if (showUpdate) {
                            Button(onClick = onUpdate, enabled = !ui.applying, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Icon(Icons.Rounded.SystemUpdateAlt, null, Modifier.size(18.dp))
                                Text(if (storeTag != null && storeTag != current) "Update to $storeTag" else "Update", Modifier.padding(start = 8.dp))
                            }
                        }
                        if (ui.updateStarted && ui.serviceIndex == 0) {
                            Hint("Update started. Reload in a minute to see the new version.")
                        }
                    }
                }
            }

            item(key = "header") {
                Column(gap.padding(top = 8.dp, start = 4.dp)) {
                    Text("Available versions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            tags == null -> "This image's versions can't be looked up. You can still type a tag below."
                            tags.image.isDockerHub -> "From Docker Hub, newest first. Tap one to switch to it."
                            else -> "From ${tags.image.registry}, highest version first. Tap one to switch to it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "search") {
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = onSearch,
                    modifier = gap.fillMaxWidth(),
                    singleLine = true,
                    enabled = !ui.applying,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = if (ui.query.isNotEmpty()) { { IconButton(onClick = { onSearch("") }) { Icon(Icons.Rounded.Close, "Clear search") } } } else null,
                    placeholder = { Text("Search, or type a tag") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { custom?.let(onPick) }),
                )
            }

            if (custom != null) {
                item(key = "custom") {
                    Surface(
                        onClick = { onPick(custom) },
                        enabled = !ui.applying,
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = gap.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Sell, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text("Use tag \"$custom\"", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(
                                    if (tags?.loading == true) "Checking the registry…" else "Not in the list. CasaOS says so if the tag doesn't exist.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }

            when {
                tags == null -> Unit
                tags.error != null && tags.page == null -> item(key = "tags-error") {
                    Column(gap.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Banner(tags.error, isError = true)
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                }
                tags.loading && tags.page == null -> item(key = "tags-loading") {
                    Box(gap.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                }
                else -> {
                    if (tags.loading) item(key = "tags-refreshing") {
                        Row(gap.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Searching…", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (tags.error != null) item(key = "tags-error-inline") { Box(gap) { Banner(tags.error, isError = true) } }
                    if (rows.isEmpty() && !tags.loading) item(key = "tags-empty") {
                        Text(
                            if (query.isEmpty()) "No versions found." else "No versions match \"$query\".",
                            gap.padding(start = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    itemsIndexed(rows, key = { _, row -> "tag:" + row.tag.name }, contentType = { _, _ -> "tag" }) { i, row ->
                        TagItem(row, first = i == 0, last = i == rows.lastIndex, enabled = !ui.applying && !row.current) { onPick(row.tag.name) }
                    }
                    item(key = "tags-footer") {
                        Column(Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val page = tags.page
                            if (page?.truncated == true && query.isEmpty()) {
                                Hint(
                                    if (page.total != null) "These are the newest ${all.size} of ${"%,d".format(page.total)} tags. Search to find older ones."
                                    else "This image has more tags than could be loaded. Search to find older ones.",
                                )
                            }
                            if (hidden > 0 || (ui.showAll && anyNoise)) {
                                TextButton(onClick = onToggleShowAll, contentPadding = PaddingValues(0.dp)) {
                                    Text(if (ui.showAll) "Hide platform-specific and signature tags" else "Show $hidden platform-specific and signature tags")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val TagLike = Regex("""[\w][\w.-]{0,127}""")

/** One tag. Consecutive rows join into a single rounded card. */
@Composable
private fun TagItem(row: TagRow, first: Boolean, last: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val radius = 24.dp
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp, topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp, bottomEnd = if (last) radius else 0.dp,
    )
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            if (!first) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, onClickLabel = "Switch to ${row.tag.name}", onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.tag.name, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val details = listOfNotNull(
                        row.tag.updatedAt?.let { DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), now, DateUtils.MINUTE_IN_MILLIS).toString() },
                        row.tag.sizeBytes?.let(::formatBytes),
                    )
                    if (details.isNotEmpty()) {
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (row.store) Badge("App Store", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                if (row.current) Badge("Current", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color) {
    Surface(Modifier.padding(start = 8.dp), shape = RoundedCornerShape(6.dp), color = bg) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = fg)
    }
}
