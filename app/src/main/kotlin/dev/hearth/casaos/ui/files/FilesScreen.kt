package dev.hearth.casaos.ui.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.FileDownloader
import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.FileIndex
import dev.hearth.casaos.data.FolderListing
import dev.hearth.casaos.data.PhoneFiles
import dev.hearth.casaos.data.PickedFile
import dev.hearth.casaos.data.SearchHits
import dev.hearth.casaos.data.INDEX_MAX_ENTRIES
import dev.hearth.casaos.data.mimeTypeOf
import dev.hearth.casaos.data.previewKind
import dev.hearth.casaos.data.renamed
import dev.hearth.casaos.data.searchFiles
import dev.hearth.casaos.data.uniqueName
import dev.hearth.casaos.data.walkFolders
import dev.hearth.casaos.data.without
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.SearchField
import dev.hearth.casaos.ui.components.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val ROOT = "/DATA"

/** A download, upload or "open with" in progress. Only one runs at a time. */
data class Transfer(val label: String, /** 0..1, or null when the total size is unknown. */ val progress: Float? = null)

/** Items cut or copied, waiting for the user to pick where they go. */
data class Clipboard(val entries: List<FileEntry>, val move: Boolean)

/** Files picked for upload, some of which already exist in the target folder. */
data class PendingUpload(val dir: String, val files: List<PickedFile>, val existing: Set<String>)

enum class UploadConflict { Replace, KeepBoth, Skip }

/** A file to hand to another app. */
data class Launch(val uri: Uri, val mime: String, val name: String, val share: Boolean)

data class FilesState(
    val path: String = ROOT,
    val listing: FolderListing? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val transfer: Transfer? = null,
    val clipboard: Clipboard? = null,
    val pendingUpload: PendingUpload? = null,
) {
    val canGoUp get() = path != ROOT && path != "/"
}

private fun join(dir: String, name: String) = dir.trimEnd('/') + "/" + name
private fun parentOf(path: String) = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
private fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "$n $many"
private fun folderName(path: String) = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

/** Null when [name] can be used for a file or folder, else why not. */
private fun nameProblem(name: String): String? = when {
    name.isBlank() -> "Enter a name"
    '/' in name -> "A name can't contain /"
    name == "." || name == ".." -> "Pick another name"
    else -> null
}

class FilesViewModel(
    private val client: CasaClient,
    private val downloader: FileDownloader,
    private val phoneFiles: PhoneFiles,
) : ViewModel() {
    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _launches = MutableSharedFlow<Launch>(extraBufferCapacity = 1)
    val launches: SharedFlow<Launch> = _launches.asSharedFlow()

    private var loadJob: Job? = null

    /** Everything on the server, for searching past the open folder. Built the first time the user searches. */
    private val _index = MutableStateFlow<FileIndex?>(null)
    val index: StateFlow<FileIndex?> = _index.asStateFlow()
    private var indexJob: Job? = null
    /** Set when files may have changed since the index was built; the next search rebuilds it. */
    private var indexStale = false

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

    fun up() { if (_state.value.canGoUp) open(parentOf(_state.value.path)) }

    fun reload() {
        indexStale = true
        open(_state.value.path)
    }

    // region search

    /** Builds the server-wide index if there's none yet or it may be out of date. Called when a search starts. */
    fun ensureIndex() {
        if (indexJob?.isActive == true) return
        if (_index.value != null && !indexStale) return
        indexStale = false
        val previous = _index.value
        // While rebuilding, keep showing the old results instead of a partial list that grows back.
        if (previous != null) _index.value = previous.copy(done = false)
        indexJob = viewModelScope.launch {
            try {
                _index.value = walkFolders(ROOT, client::folder) { partial -> if (previous == null) _index.value = partial }
            } catch (e: CasaException) {
                _messages.emit("Couldn't search the whole server: ${e.message}")
                _index.update { it?.copy(done = true) }
                indexStale = true
            }
        }
    }

    /** Patches the index after a change on the server, or marks it for a rebuild if a walk is running. */
    private fun patchIndex(change: (FileIndex) -> FileIndex) {
        if (indexJob?.isActive == true) indexStale = true
        _index.update { it?.let(change) }
    }

    /** Names already used in [dir], to warn before a rename or new folder would collide. */
    fun namesIn(dir: String): Set<String> {
        val listing = _state.value.listing
        if (listing != null && listing.path == dir) return listing.entries.map { it.name }.toSet()
        return _index.value?.entries.orEmpty().filter { parentOf(it.path) == dir }.map { it.name }.toSet()
    }

    // endregion

    /** Reloads only if the user is still looking at [dir]. */
    private fun reloadIfShowing(dir: String) { if (_state.value.path == dir) reload() }

    // region transfers

    /** Runs one transfer, reporting progress and turning failures into a message. */
    private fun transfer(label: String, failure: String, block: suspend (onProgress: (Long, Long) -> Unit) -> Unit) {
        if (_state.value.transfer != null) {
            _messages.tryEmit("Wait for the current transfer to finish")
            return
        }
        _state.update { it.copy(transfer = Transfer(label)) }
        viewModelScope.launch {
            try {
                block { done, total ->
                    _state.update { s -> s.copy(transfer = s.transfer?.copy(progress = if (total > 0) done.toFloat() / total else null)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Storage can fail in several ways (disk full, MediaStore refusing the write, file removed
                // mid-transfer): show a message instead of crashing the app.
                _messages.emit("$failure: ${e.message ?: "storage error"}")
            } finally {
                _state.update { it.copy(transfer = null) }
            }
        }
    }

    fun download(entry: FileEntry) = transfer("Downloading ${entry.name}", "Download failed") { progress ->
        downloader.save(entry, progress)
        _messages.emit("Saved ${entry.name} to Downloads/Hearth")
    }

    /** Fetches the file and hands it to another app to open ([share] false) or to share. */
    fun openWith(entry: FileEntry, share: Boolean) =
        transfer("Getting ${entry.name}", if (share) "Couldn't share" else "Couldn't open") { progress ->
            val uri = phoneFiles.fetchForSharing(entry, progress)
            _launches.emit(Launch(uri, mimeTypeOf(entry.name), entry.name, share))
        }

    /** Starts uploading [uris] into the open folder, first asking what to do if some names are taken. */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_state.value.transfer != null) {
            _messages.tryEmit("Wait for the current transfer to finish")
            return
        }
        val dir = _state.value.path
        viewModelScope.launch {
            val files = uris.mapNotNull { phoneFiles.describe(it) }
            if (files.size < uris.size) _messages.emit("Couldn't read ${plural(uris.size - files.size, "file")}")
            if (files.isEmpty()) return@launch
            // The listing may be out of date, but it's what the user sees; the server would replace silently.
            val taken = _state.value.listing?.takeIf { it.path == dir }?.entries?.map { it.name }?.toSet().orEmpty()
            val existing = files.map { it.name }.filter { it in taken }.toSet()
            if (existing.isEmpty()) startUpload(dir, files, UploadConflict.Replace, taken)
            else _state.update { it.copy(pendingUpload = PendingUpload(dir, files, existing)) }
        }
    }

    fun resolveUpload(choice: UploadConflict?) {
        val pending = _state.value.pendingUpload ?: return
        _state.update { it.copy(pendingUpload = null) }
        if (choice == null) return
        val taken = _state.value.listing?.takeIf { it.path == pending.dir }?.entries?.map { it.name }?.toSet().orEmpty()
        startUpload(pending.dir, pending.files, choice, taken)
    }

    private fun startUpload(dir: String, picked: List<PickedFile>, conflict: UploadConflict, taken: Set<String>) {
        val used = taken.toMutableSet()
        // Decide every target name up front, so two picked files with the same name don't overwrite each other.
        val plan = picked.mapNotNull { f ->
            when {
                f.name !in used -> f to f.name
                conflict == UploadConflict.Skip -> null
                conflict == UploadConflict.Replace -> f to f.name
                else -> f to uniqueName(f.name, used)
            }?.also { used += it.second }
        }
        if (plan.isEmpty()) {
            _messages.tryEmit("Nothing to upload: those files are already there")
            return
        }
        val folder = dir.substringAfterLast('/')
        _state.update { it.copy(transfer = Transfer(uploadLabel(plan[0].second, 0, plan.size))) }
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            var lastError: String? = null
            try {
                plan.forEachIndexed { i, (file, name) ->
                    _state.update { it.copy(transfer = Transfer(uploadLabel(name, i, plan.size))) }
                    try {
                        phoneFiles.upload(file, dir, name) { done, total ->
                            _state.update { s -> s.copy(transfer = s.transfer?.copy(progress = if (total > 0) done.toFloat() / total else null)) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += name
                        lastError = e.message
                    }
                }
                val done = plan.size - failed.size
                _messages.emit(
                    when {
                        failed.isEmpty() -> "Uploaded ${plural(done, "file")} to $folder"
                        done == 0 -> "Upload failed: ${lastError ?: "unknown error"}"
                        else -> "Uploaded $done of ${plan.size}. ${failed.first()} failed: ${lastError ?: "unknown error"}"
                    },
                )
            } finally {
                _state.update { it.copy(transfer = null) }
                indexStale = true
                reloadIfShowing(dir)
            }
        }
    }

    private fun uploadLabel(name: String, index: Int, count: Int) =
        if (count == 1) "Uploading $name" else "Uploading $name (${index + 1} of $count)"

    // endregion

    // region file actions

    /** Runs a quick change on the server, then reloads the folder. Returns false if it failed. */
    private suspend fun change(failure: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CasaException) {
        _messages.emit("$failure: ${e.message}")
        false
    }

    fun createFolder(name: String) {
        val dir = _state.value.path
        viewModelScope.launch {
            if (change("Couldn't create the folder") { client.createFolder(join(dir, name.trim())) }) {
                indexStale = true
                reloadIfShowing(dir)
            }
        }
    }

    fun rename(entry: FileEntry, newName: String) {
        val name = newName.trim()
        if (name == entry.name) return
        val dir = parentOf(entry.path)
        viewModelScope.launch {
            val newPath = join(dir, name)
            if (change("Couldn't rename ${entry.name}") { client.rename(entry.path, newPath) }) {
                patchIndex { it.renamed(entry.path, newPath) }
                reloadIfShowing(dir)
            }
        }
    }

    fun delete(entries: List<FileEntry>) {
        if (entries.isEmpty()) return
        val dir = parentOf(entries[0].path)
        viewModelScope.launch {
            if (change("Couldn't delete") { client.delete(entries.map { it.path }) }) {
                patchIndex { it.without(entries.map { e -> e.path }) }
                _messages.emit(if (entries.size == 1) "Deleted ${entries[0].name}" else "Deleted ${plural(entries.size, "item")}")
                reloadIfShowing(dir)
            }
        }
    }

    fun cut(entries: List<FileEntry>, move: Boolean) = _state.update { it.copy(clipboard = Clipboard(entries, move)) }

    fun clearClipboard() = _state.update { it.copy(clipboard = null) }

    /** Copies or moves the clipboard into the open folder. */
    fun paste() {
        val clip = _state.value.clipboard ?: return
        val dest = _state.value.path
        val problem = when {
            clip.entries.any { parentOf(it.path) == dest } -> if (clip.move) "That's already in this folder" else "Open a different folder to copy into"
            clip.entries.any { it.isDir && (dest == it.path || dest.startsWith(it.path.trimEnd('/') + "/")) } -> "A folder can't go inside itself"
            else -> null
        }
        if (problem != null) {
            _messages.tryEmit(problem)
            return
        }
        _state.update { it.copy(clipboard = null) }
        viewModelScope.launch {
            val what = if (clip.entries.size == 1) clip.entries[0].name else plural(clip.entries.size, "item")
            val ok = change(if (clip.move) "Couldn't move" else "Couldn't copy") {
                client.transfer(clip.entries.map { it.path }, dest, move = clip.move)
            }
            if (!ok) return@launch
            indexStale = true
            // The server does the work in the background: give small items a moment to land before reloading.
            _messages.emit(if (clip.move) "Moving $what here" else "Copying $what here. Big folders take a while; pull down to refresh.")
            delay(800)
            reloadIfShowing(dest)
        }
    }

    // endregion

    // region previews

    fun fileUrl(entry: FileEntry): String = client.fileUrl(entry.path)

    suspend fun streamHeaders(): Result<Map<String, String>> =
        try { Result.success(client.authHeadersForStreaming()) } catch (e: CasaException) { Result.failure(e) }

    suspend fun readText(entry: FileEntry): Result<String> = try {
        val bytes = client.readStart(entry.path, TEXT_PREVIEW_BYTES)
        Result.success(bytes.toString(Charsets.UTF_8))
    } catch (e: CasaException) {
        Result.failure(e)
    }

    // endregion
}

const val TEXT_PREVIEW_BYTES = 256 * 1024

/** What a file's menu or a dialog is acting on. */
private sealed interface FileDialog {
    data object NewFolder : FileDialog
    data class Rename(val entry: FileEntry) : FileDialog
    data class Delete(val entry: FileEntry) : FileDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: FilesViewModel, snackbar: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val index by viewModel.index.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    var previewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    // Searches the open folder and the whole server; opening another folder starts a fresh search.
    var query by rememberSaveable(state.path) { mutableStateOf("") }
    val q = query.trim()
    val searching = q.isNotEmpty()
    LaunchedEffect(searching) { if (searching) viewModel.ensureIndex() }
    // Matches outside the open folder. Filtering a big index takes a moment, so it runs off the main thread.
    val elsewhere by produceState(SearchHits(emptyList(), 0), index, q, state.path) {
        val entries = index?.entries.orEmpty()
        value = withContext(Dispatchers.Default) { searchFiles(entries, q, skipDir = state.path) }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { viewModel.upload(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.upload(it) }

    BackHandler(enabled = state.canGoUp) { viewModel.up() }
    // Registered later, so it wins: back clears a search before going up a folder.
    BackHandler(enabled = query.isNotEmpty()) { query = "" }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) {
        viewModel.launches.collect { l ->
            val target = if (l.share) {
                Intent(Intent.ACTION_SEND).setType(l.mime).putExtra(Intent.EXTRA_STREAM, l.uri)
            } else {
                Intent(Intent.ACTION_VIEW).setDataAndType(l.uri, l.mime)
            }
            // Through the chooser, the grant only reaches the picked app if the link is in the intent's clip data.
            target.clipData = ClipData.newRawUri(l.name, l.uri)
            target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(target, if (l.share) "Share ${l.name}" else "Open ${l.name} with")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(chooser)
            } catch (_: ActivityNotFoundException) {
                snackbar.showSnackbar("No app on this phone can open ${l.name}")
            }
        }
    }

    fun openEntry(entry: FileEntry) {
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        when {
            entry.isDir -> viewModel.open(entry.path)
            previewKind(entry.name) != null -> previewPath = entry.path
            else -> viewModel.openWith(entry, share = false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
                Text("Files", style = MaterialTheme.typography.headlineMedium)
                Breadcrumbs(state.path, onNavigate = viewModel::open)
                if (state.listing != null || query.isNotEmpty()) {
                    SearchField(query, { query = it }, "Search all files on the server", Modifier.padding(top = 4.dp))
                }
            }
            state.transfer?.let { TransferBar(it) }
            state.clipboard?.let { clip ->
                ClipboardBar(clip, onCancel = viewModel::clearClipboard, onPaste = viewModel::paste)
            }

            PullToRefreshBox(
                isRefreshing = state.loading && state.listing != null,
                onRefresh = { viewModel.reload(); if (searching) viewModel.ensureIndex() },
                modifier = Modifier.weight(1f),
            ) {
                val listing = state.listing
                when {
                    listing == null && state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    listing == null -> EmptyState("Couldn't open this folder", state.error.orEmpty()) {
                        TextButton(onClick = viewModel::reload) { Text("Try again") }
                    }
                    listing.entries.isEmpty() && !searching ->
                        EmptyState("Empty folder", "There's nothing in ${state.path}. Tap + to upload files or make a folder.")
                    else -> {
                        val shown = remember(listing, q) { if (q.isEmpty()) listing.entries else listing.entries.filter { it.name.contains(q, true) } }
                        val onAction: (FileEntry, FileAction) -> Unit = { entry, action ->
                            when (action) {
                                FileAction.OpenWith -> viewModel.openWith(entry, share = false)
                                FileAction.Share -> viewModel.openWith(entry, share = true)
                                FileAction.Download -> viewModel.download(entry)
                                FileAction.Rename -> dialog = FileDialog.Rename(entry)
                                FileAction.Copy -> viewModel.cut(listOf(entry), move = false)
                                FileAction.Move -> viewModel.cut(listOf(entry), move = true)
                                FileAction.Delete -> dialog = FileDialog.Delete(entry)
                            }
                        }
                        LazyColumn(
                            // Room at the bottom so the last row isn't under the + button.
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (searching && shown.isNotEmpty()) {
                                item(key = "here-header") { SectionLabel("In ${folderName(state.path)}") }
                            }
                            items(shown, key = { it.path }) { entry ->
                                FileRow(entry, onClick = { openEntry(entry) }, onAction = { onAction(entry, it) })
                            }
                            if (searching) {
                                item(key = "elsewhere-header") { SectionLabel("Everywhere else") }
                                item(key = "elsewhere-status") {
                                    SearchStatus(index, elsewhere, nothingHere = shown.isEmpty(), query = q)
                                }
                                items(elsewhere.entries, key = { "elsewhere:" + it.path }) { entry ->
                                    FileRow(
                                        entry,
                                        location = locationOf(entry),
                                        onClick = { openEntry(entry) },
                                        onAction = { onAction(entry, it) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            FloatingActionButton(onClick = { addMenu = true }) { Icon(Icons.Rounded.Add, "Upload or create a folder") }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Upload photos & videos") },
                    leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) },
                    onClick = {
                        addMenu = false
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Upload files") },
                    leadingIcon = { Icon(Icons.Rounded.UploadFile, null) },
                    onClick = { addMenu = false; pickFiles.launch(arrayOf("*/*")) },
                )
                DropdownMenuItem(
                    text = { Text("New folder") },
                    leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                    onClick = { addMenu = false; dialog = FileDialog.NewFolder },
                )
            }
        }
    }

    when (val d = dialog) {
        FileDialog.NewFolder -> NameDialog(
            title = "New folder",
            initial = "",
            confirm = "Create",
            taken = state.listing?.entries?.map { it.name }?.toSet().orEmpty(),
            onDismiss = { dialog = null },
            onConfirm = { viewModel.createFolder(it); dialog = null },
        )
        is FileDialog.Rename -> NameDialog(
            title = "Rename",
            initial = d.entry.name,
            confirm = "Rename",
            taken = viewModel.namesIn(parentOf(d.entry.path)) - d.entry.name,
            onDismiss = { dialog = null },
            onConfirm = { viewModel.rename(d.entry, it); dialog = null },
        )
        is FileDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${d.entry.name}?") },
            text = {
                Text(
                    if (d.entry.isDir) "The folder and everything in it are deleted from the server. This can't be undone."
                    else "The file is deleted from the server. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(listOf(d.entry)); dialog = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }

    state.pendingUpload?.let { UploadConflictDialog(it, onChoice = viewModel::resolveUpload) }

    // A preview can be opened from the folder or from search results anywhere on the server.
    val previewEntry = previewPath?.let { p ->
        state.listing?.entries?.firstOrNull { it.path == p } ?: index?.entries?.firstOrNull { it.path == p }
    }
    if (previewEntry != null) {
        FilePreview(
            entry = previewEntry,
            viewModel = viewModel,
            transfer = state.transfer,
            onClose = { previewPath = null },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** How far the server-wide search has got, or why there's nothing (more) to show. */
@Composable
private fun SearchStatus(index: FileIndex?, hits: SearchHits, nothingHere: Boolean, query: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodyMedium
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (index == null || !index.done) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                val scanned = index?.foldersScanned ?: 0
                Text(
                    if (scanned == 0) "Searching the server…" else "Searching the server… ${plural(scanned, "folder")} checked",
                    Modifier.padding(start = 10.dp),
                    style = style,
                    color = muted,
                )
            }
        } else if (hits.total == 0) {
            Text(
                if (nothingHere) "Nothing on the server matches \"$query\"." else "No other matches on the server.",
                style = style,
                color = muted,
            )
        }
        if (hits.total > hits.entries.size) {
            Text("Showing the best ${hits.entries.size} of ${hits.total} matches. Type more to narrow it down.", style = style, color = muted)
        }
        if (index?.truncated == true) {
            Text("The server has a lot of files: only the first ${"%,d".format(INDEX_MAX_ENTRIES)} were searched.", style = style, color = muted)
        }
    }
}

/** The folder an entry is in, relative to the top of Files, e.g. "Media/Movies". */
private fun locationOf(entry: FileEntry): String =
    parentOf(entry.path).removePrefix(ROOT).trim('/').ifEmpty { folderName(ROOT) }

@Composable
private fun TransferBar(t: Transfer) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(t.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val p = t.progress
        if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(top = 6.dp))
        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

/** Shown while items are cut or copied: browse to the destination, then paste. */
@Composable
private fun ClipboardBar(clip: Clipboard, onCancel: () -> Unit, onPaste: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val what = if (clip.entries.size == 1) clip.entries[0].name else plural(clip.entries.size, "item")
            Column(Modifier.weight(1f)) {
                Text(if (clip.move) "Moving" else "Copying", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(what, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onPaste) { Text(if (clip.move) "Move here" else "Paste here") }
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

private enum class FileAction(val label: String, val icon: ImageVector, val filesOnly: Boolean = false) {
    OpenWith("Open with…", Icons.AutoMirrored.Rounded.OpenInNew, filesOnly = true),
    Share("Share", Icons.Rounded.Share, filesOnly = true),
    Download("Download", Icons.Rounded.Download, filesOnly = true),
    Rename("Rename", Icons.Rounded.DriveFileRenameOutline),
    Copy("Copy to…", Icons.Rounded.ContentCopy),
    Move("Move to…", Icons.AutoMirrored.Rounded.DriveFileMove),
    Delete("Delete", Icons.Rounded.Delete),
}

/** Tap opens; long-press or the ⋮ button shows the item's actions. */
@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit, onAction: (FileAction) -> Unit, location: String? = null) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box {
        HearthCard(Modifier.fillMaxWidth(), onClick = onClick, onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menu = true
        }) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    iconFor(entry),
                    null,
                    Modifier.size(28.dp),
                    tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = remember(entry) {
                        listOfNotNull(if (entry.isDir) null else formatBytes(entry.size), formatModified(entry.modified)).joinToString(" · ")
                    }
                    if (location != null) {
                        Text(
                            "in $location",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Actions for ${entry.name}") }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(14.dp, 0.dp)) {
            FileAction.entries.filter { !it.filesOnly || !entry.isDir }.forEach { action ->
                if (action == FileAction.Rename && !entry.isDir) HorizontalDivider()
                if (action == FileAction.Delete) HorizontalDivider()
                val danger = action == FileAction.Delete
                DropdownMenuItem(
                    text = { Text(action.label, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
                    leadingIcon = { Icon(action.icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { menu = false; onAction(action) },
                )
            }
        }
    }
}

/** Asks for a file or folder name, refusing ones that can't be used or are already taken. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Select the name without its extension, so typing replaces just that, as file managers do.
    val stem = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stem))) }
    val focus = remember { FocusRequester() }
    val name = value.text.trim()
    val problem = nameProblem(name) ?: if (name in taken) "Something with that name is already here" else null
    val changed = name != initial
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = problem != null && value.text.isNotEmpty() && changed,
                supportingText = { if (problem != null && value.text.isNotEmpty() && changed) Text(problem) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (problem == null && changed) onConfirm(name) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = problem == null && changed) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UploadConflictDialog(pending: PendingUpload, onChoice: (UploadConflict?) -> Unit) {
    val n = pending.existing.size
    val folder = pending.dir.substringAfterLast('/')
    AlertDialog(
        onDismissRequest = { onChoice(null) },
        title = { Text(if (n == 1) "${pending.existing.first()} is already in $folder" else "$n files are already in $folder") },
        text = {
            Column {
                Text("What should happen to ${if (n == 1) "it" else "them"}?", Modifier.padding(bottom = 8.dp))
                ConflictOption("Skip", "Upload only the new files", onClick = { onChoice(UploadConflict.Skip) })
                ConflictOption("Keep both", "Upload with a new name, like photo (1).jpg", onClick = { onChoice(UploadConflict.KeepBoth) })
                ConflictOption("Replace", "Overwrite the files on the server", onClick = { onChoice(UploadConflict.Replace) })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onChoice(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun ConflictOption(title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
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
