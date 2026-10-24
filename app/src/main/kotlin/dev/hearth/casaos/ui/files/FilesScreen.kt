package dev.hearth.casaos.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.FileDownloader
import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.FolderListing
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val ROOT = "/DATA"

data class FilesState(
    val path: String = ROOT,
    val listing: FolderListing? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val downloading: String? = null,
    /** 0..1, or null when the total size is unknown. */
    val progress: Float? = null,
) {
    val canGoUp get() = path != ROOT && path != "/"
}

class FilesViewModel(private val client: CasaClient, private val downloader: FileDownloader) : ViewModel() {
    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var loadJob: Job? = null

    init { open(ROOT) }

    fun open(path: String) {
        loadJob?.cancel()
        _state.update { it.copy(path = path, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val listing = client.folder(path)
                _state.update { it.copy(listing = listing, loading = false) }
            } catch (e: CasaException) {
                _state.update { it.copy(listing = null, loading = false, error = e.message) }
            }
        }
    }

    fun up() { if (_state.value.canGoUp) open(_state.value.path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }) }

    fun reload() = open(_state.value.path)

    fun download(entry: FileEntry) {
        if (_state.value.downloading != null) return
        _state.update { it.copy(downloading = entry.name, progress = null) }
        viewModelScope.launch {
            try {
                downloader.save(entry) { done, total ->
                    _state.update { it.copy(progress = if (total > 0) done.toFloat() / total else null) }
                }
                _messages.emit("Saved ${entry.name} to Downloads/Hearth")
            } catch (e: CasaException) {
                _messages.emit("Download failed: ${e.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Storage can fail in several ways (disk full, MediaStore refusing the write, file removed
                // mid-transfer): show a message instead of crashing the app.
                _messages.emit("Download failed: ${e.message ?: "storage error"}")
            } finally {
                _state.update { it.copy(downloading = null, progress = null) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: FilesViewModel, snackbar: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = state.canGoUp) { viewModel.up() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
            Text("Files", style = MaterialTheme.typography.headlineMedium)
            Breadcrumbs(state.path, onNavigate = viewModel::open)
        }
        if (state.downloading != null) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("Downloading ${state.downloading}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val p = state.progress
                if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(top = 6.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }

        PullToRefreshBox(isRefreshing = state.loading && state.listing != null, onRefresh = viewModel::reload, modifier = Modifier.weight(1f)) {
            val listing = state.listing
            when {
                listing == null && state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                listing == null -> EmptyState("Couldn't open this folder", state.error.orEmpty()) {
                    TextButton(onClick = viewModel::reload) { Text("Try again") }
                }
                listing.entries.isEmpty() -> EmptyState("Empty folder", "There's nothing in ${state.path}.")
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(listing.entries, key = { it.path }) { entry ->
                        FileRow(
                            entry,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                if (entry.isDir) viewModel.open(entry.path) else viewModel.download(entry)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    Row(Modifier.horizontalScroll(rememberScrollState(Int.MAX_VALUE)), verticalAlignment = Alignment.CenterVertically) {
        segments.forEachIndexed { i, name ->
            val target = "/" + segments.take(i + 1).joinToString("/")
            val last = i == segments.lastIndex
            Text(
                name,
                modifier = Modifier.clickable(enabled = !last) { onNavigate(target) }.padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            )
            if (!last) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit) {
    HearthCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                iconFor(entry),
                null,
                Modifier.size(28.dp),
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(if (entry.isDir) null else formatBytes(entry.size), formatModified(entry.modified)).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (entry.isDir) {
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
            } else {
                IconButton(onClick = onClick) { Icon(Icons.Rounded.Download, "Download ${entry.name}") }
            }
        }
    }
}

private fun iconFor(e: FileEntry): ImageVector {
    if (e.isDir) return Icons.Rounded.Folder
    return when (e.name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> Icons.Rounded.Image
        "mp4", "mkv", "mov", "avi", "webm" -> Icons.Rounded.Movie
        "mp3", "flac", "wav", "m4a", "ogg", "opus" -> Icons.Rounded.Audiotrack
        "zip", "rar", "7z", "tar", "gz", "xz", "bz2" -> Icons.Rounded.FolderZip
        "pdf", "txt", "md", "doc", "docx", "odt", "csv", "xls", "xlsx" -> Icons.Rounded.Description
        "apk" -> Icons.Rounded.Android
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

private fun formatModified(iso: String?): String? =
    iso?.let { runCatching { dateFormat.format(Instant.parse(it)) }.getOrNull() }
