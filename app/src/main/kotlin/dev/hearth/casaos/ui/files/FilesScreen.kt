package dev.hearth.casaos.ui.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.hearth.casaos.data.hasThumbnail
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.FileDownloader
import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.FileIndex
import dev.hearth.casaos.data.FileSort
import dev.hearth.casaos.data.FolderListing
import dev.hearth.casaos.data.PhoneFiles
import dev.hearth.casaos.data.PickedFile
import dev.hearth.casaos.data.PrefsStore
import dev.hearth.casaos.data.SearchHits
import dev.hearth.casaos.data.SortBy
import dev.hearth.casaos.data.Transfer
import dev.hearth.casaos.data.TransferProgress
import dev.hearth.casaos.data.Transfers
import dev.hearth.casaos.data.arranged
import dev.hearth.casaos.data.isHidden
import dev.hearth.casaos.data.isHiddenPath
import dev.hearth.casaos.data.INDEX_MAX_ENTRIES
import dev.hearth.casaos.data.mimeTypeOf
import dev.hearth.casaos.data.previewKind
import dev.hearth.casaos.data.renamed
import dev.hearth.casaos.data.searchFiles
import dev.hearth.casaos.data.uniqueName
import dev.hearth.casaos.data.walkFolders
import dev.hearth.casaos.data.without
import dev.hearth.casaos.data.withoutNested
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import dev.hearth.casaos.ui.components.ReadableGutter
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.GroupGap
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.IconTile
import dev.hearth.casaos.ui.components.ScreenHeader
import dev.hearth.casaos.ui.components.SearchField
import dev.hearth.casaos.ui.components.SectionHeader
import dev.hearth.casaos.ui.components.groupShape
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
    val searchError: String? = null,
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
    private val prefs: PrefsStore,
    private val transfers: Transfers,
    /** Whether Hearth is on screen, rather than in the background. */
    private val inFront: () -> Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * A file fetched for "Open with" or "Share", waiting for the screen to hand it on. Held until then, since
     * the fetch can finish while Hearth is in the background or another tab is open, where Android won't let
     * Hearth open the app picker.
     */
    private val _launch = MutableStateFlow<Launch?>(null)
    val launch: StateFlow<Launch?> = _launch.asStateFlow()

    /** The screen handed [launch] on. */
    fun launched() {
        _launch.value = null
    }

    private var loadJob: Job? = null

    /** Everything on the server, for searching past the open folder. Built the first time the user searches. */
    private val _index = MutableStateFlow<FileIndex?>(null)
    val index: StateFlow<FileIndex?> = _index.asStateFlow()
    private var indexJob: Job? = null
    /** Set when files may have changed since the index was built; the next search rebuilds it. */
    private var indexStale = false

    val sort: StateFlow<FileSort> = prefs.fileSort

    init {
        open(ROOT)
        viewModelScope.launch { transfers.current.collect { t -> _state.update { it.copy(transfer = t) } } }
    }

    fun sortBy(by: SortBy) = prefs.setFileSort(sort.value.pick(by))

    fun setShowHidden(show: Boolean) = prefs.setFileSort(sort.value.copy(showHidden = show))

    /** Whether folders show as a grid of thumbnails rather than a list. */
    val thumbnails: StateFlow<Boolean> = prefs.fileThumbnails

    fun setThumbnails(on: Boolean) = prefs.setFileThumbnails(on)

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
        _state.update { it.copy(searchError = null) }
        val previous = _index.value
        // While rebuilding, keep showing the old results instead of a partial list that grows back.
        if (previous != null) _index.value = previous.copy(done = false)
        indexJob = viewModelScope.launch {
            try {
                _index.value = walkFolders(ROOT, client::folder) { partial -> if (previous == null) _index.value = partial }
            } catch (e: CasaException) {
                _index.update { it?.copy(done = true) }
                indexStale = true
                _state.update { it.copy(searchError = "Couldn't search the whole server: ${e.message}") }
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

    /**
     * Runs one transfer, which keeps going if this screen is closed or Hearth goes to the background.
     * [block] returns what to tell the user once it's done, if anything.
     */
    private fun transfer(
        label: String,
        failure: String,
        cancelled: String = "Cancelled",
        block: suspend (TransferProgress) -> String?,
    ) {
        val started = transfers.start(label, failure, cancelled, onMessage = { _messages.tryEmit(it) }, work = block)
        if (!started) _messages.tryEmit("Wait for the current transfer to finish")
    }

    /** Stops the download, upload or "open with" under way. A partly downloaded file is removed from the phone. */
    fun cancelTransfer() = transfers.cancel()

    fun download(entry: FileEntry) = transfer("Downloading ${entry.name}", "Download failed") { progress ->
        downloader.save(entry, progress::update)
        "Saved ${entry.name} to Downloads/Hearth"
    }

    /** Fetches the file and hands it to another app to open ([share] false) or to share. */
    fun openWith(entry: FileEntry, share: Boolean) =
        transfer("Getting ${entry.name}", if (share) "Couldn't share" else "Couldn't open") { progress ->
            val uri = phoneFiles.fetchForSharing(entry, progress::update)
            _launch.value = Launch(uri, mimeTypeOf(entry.name), entry.name, share)
            // On screen, the app picker opening says it all. In the background, the notification says it's
            // ready, and the picker opens when Hearth is back.
            if (inFront()) null else "${entry.name} is ready to ${if (share) "share" else "open"}"
        }

    /** Starts uploading [uris] into the open folder, first asking what to do if some names are taken. */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (transfers.current.value != null) {
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
        transfer(uploadLabel(plan[0].second, 0, plan.size), "Upload failed", cancelled = "Upload cancelled") { progress ->
            val failed = mutableListOf<String>()
            var lastError: String? = null
            try {
                plan.forEachIndexed { i, (file, name) ->
                    progress.label(uploadLabel(name, i, plan.size))
                    try {
                        phoneFiles.upload(file, dir, name, progress::update)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += name
                        lastError = e.message
                    }
                }
            } finally {
                indexStale = true
                reloadIfShowing(dir)
            }
            val done = plan.size - failed.size
            when {
                failed.isEmpty() -> "Uploaded ${plural(done, "file")} to $folder"
                done == 0 -> "Upload failed: ${lastError ?: "unknown error"}"
                else -> "Uploaded $done of ${plan.size}. ${failed.first()} failed: ${lastError ?: "unknown error"}"
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
        viewModelScope.launch {
            if (change("Couldn't delete") { client.delete(entries.map { it.path }) }) {
                patchIndex { it.without(entries.map { e -> e.path }) }
                _messages.emit(if (entries.size == 1) "Deleted ${entries[0].name}" else "Deleted ${plural(entries.size, "item")}")
                // Search results can come from any folder, including ones above the open folder.
                val here = _state.value.path
                val gone = entries.firstOrNull { here == it.path || here.startsWith(it.path.trimEnd('/') + "/") }
                if (gone != null) open(parentOf(gone.path))
                else if (entries.any { parentOf(it.path) == here }) reload()
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
            clip.entries.any { parentOf(it.path) == dest } -> when {
                !clip.move -> "Open a different folder to copy into"
                clip.entries.size == 1 -> "That's already in this folder"
                else -> "Those are already in this folder"
            }
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
    data class Delete(val entries: List<FileEntry>) : FileDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: FilesViewModel, snackbar: SnackbarHostState, locked: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val index by viewModel.index.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val thumbnails by viewModel.thumbnails.collectAsStateWithLifecycle()
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
    // Items ticked for copying, moving or deleting together, by path. Opening another folder starts over.
    var selected by remember(state.path) { mutableStateOf(emptyMap<String, FileEntry>()) }
    val selecting = selected.isNotEmpty()
    val shown = remember(state.listing, q, sort) {
        val arranged = state.listing?.entries.orEmpty().arranged(sort)
        if (q.isEmpty()) arranged else arranged.filter { it.name.contains(q, true) }
    }
    // After a refresh, forget ticked items that are no longer in the folder.
    LaunchedEffect(state.listing) {
        val listing = state.listing ?: return@LaunchedEffect
        val paths = listing.entries.mapTo(HashSet()) { it.path }
        if (selected.keys.any { parentOf(it) == listing.path && it !in paths }) {
            selected = selected.filterKeys { parentOf(it) != listing.path || it in paths }
        }
    }
    // Matches outside the open folder. Filtering a big index takes a moment, so it runs off the main thread.
    val elsewhere by produceState(SearchHits(emptyList(), 0), index, q, state.path, sort.showHidden) {
        val entries = index?.entries.orEmpty()
        val showHidden = sort.showHidden
        value = withContext(Dispatchers.Default) {
            searchFiles(if (showHidden) entries else entries.filterNot { isHiddenPath(it.path, ROOT) }, q, skipDir = state.path)
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { viewModel.upload(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.upload(it) }

    BackHandler(enabled = state.canGoUp) { viewModel.up() }
    // Registered later, so it wins: back clears a search before going up a folder.
    BackHandler(enabled = query.isNotEmpty()) { query = "" }
    BackHandler(enabled = selecting) { selected = emptyMap() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    // Only while Hearth is in front and unlocked: Android ignores the picker from the background, and over the
    // lock screen it would let the file out without the fingerprint or PIN.
    val pendingLaunch by viewModel.launch.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val scope = rememberCoroutineScope()
    LaunchedEffect(pendingLaunch, locked) {
        val l = pendingLaunch ?: return@LaunchedEffect
        if (locked) return@LaunchedEffect
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
            // Not in this effect, which clearing the launch below restarts.
            scope.launch { snackbar.showSnackbar("No app on this phone can open ${l.name}") }
        }
        viewModel.launched()
    }

    fun toggle(entry: FileEntry) {
        selected = if (entry.path in selected) selected - entry.path else selected + (entry.path to entry)
    }

    /** What a bulk action works on: a folder already covers ticked items inside it. */
    fun takeSelection(): List<FileEntry> = selected.values.toList().withoutNested().also { selected = emptyMap() }

    fun openEntry(entry: FileEntry) {
        if (selecting) return toggle(entry)
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        when {
            entry.isDir -> viewModel.open(entry.path)
            previewKind(entry.name) != null -> previewPath = entry.path
            else -> viewModel.openWith(entry, share = false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        ReadableGutter(min = 20.dp) { side ->
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(start = side, end = side, top = 12.dp, bottom = 8.dp)) {
                    ScreenHeader(
                        if (selecting) "${selected.size} selected" else "Files",
                        subtitle = { Breadcrumbs(state.path, onNavigate = viewModel::open) },
                    ) {
                        if (selecting) {
                            IconButton(onClick = {
                                val visible = if (searching) shown + elsewhere.entries else shown
                                selected = selected + visible.associateBy { it.path }
                            }) { Icon(Icons.Rounded.SelectAll, "Select all") }
                            IconButton(onClick = { selected = emptyMap() }) { Icon(Icons.Rounded.Close, "Clear selection") }
                        } else {
                            // Shows the view a tap switches to, as file managers do.
                            IconButton(onClick = { viewModel.setThumbnails(!thumbnails) }) {
                                if (thumbnails) Icon(Icons.AutoMirrored.Rounded.ViewList, "Show as a list")
                                else Icon(Icons.Rounded.GridView, "Show as thumbnails")
                            }
                            SortButton(sort, onSortBy = viewModel::sortBy, onShowHidden = viewModel::setShowHidden)
                        }
                    }
                    if (state.listing != null || query.isNotEmpty()) {
                        SearchField(query, { query = it }, "Search all files on the server", Modifier.padding(top = 8.dp))
                    }
                }
                state.transfer?.let { TransferBar(it, side, onCancel = viewModel::cancelTransfer) }
                state.clipboard?.let { clip ->
                    ClipboardBar(clip, side, onCancel = viewModel::clearClipboard, onPaste = viewModel::paste)
                }

                PullToRefreshBox(
                    isRefreshing = state.loading && state.listing != null,
                    onRefresh = { viewModel.reload(); if (searching) viewModel.ensureIndex() },
                    modifier = Modifier.weight(1f),
                ) {
                    val listing = state.listing
                    when {
                        listing == null && state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                        listing == null -> EmptyState("Couldn't open this folder", state.error.orEmpty(), icon = Icons.Rounded.CloudOff) {
                            TextButton(onClick = viewModel::reload) { Text("Try again") }
                        }
                        listing.entries.isEmpty() && !searching ->
                            EmptyState(
                                "Empty folder",
                                "There's nothing in ${state.path}. Tap + to upload files or make a folder.",
                                icon = Icons.Rounded.FolderOpen,
                            )
                        !sort.showHidden && !searching && listing.entries.all { isHidden(it.name) } ->
                            EmptyState("Only hidden files", "Everything in ${state.path} is hidden.", icon = Icons.Rounded.VisibilityOff) {
                                TextButton(onClick = { viewModel.setShowHidden(true) }) { Text("Show hidden files") }
                            }
                        else -> {
                            val onAction: (FileEntry, FileAction) -> Unit = { entry, action ->
                                when (action) {
                                    FileAction.Select -> toggle(entry)
                                    FileAction.OpenWith -> viewModel.openWith(entry, share = false)
                                    FileAction.Share -> viewModel.openWith(entry, share = true)
                                    FileAction.Download -> viewModel.download(entry)
                                    FileAction.Rename -> dialog = FileDialog.Rename(entry)
                                    FileAction.Copy -> viewModel.cut(listOf(entry), move = false)
                                    FileAction.Move -> viewModel.cut(listOf(entry), move = true)
                                    FileAction.Delete -> dialog = FileDialog.Delete(listOf(entry))
                                }
                            }
                            // Room at the bottom so the last item isn't under the + button or the selection's actions.
                            val padding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 96.dp)
                            if (thumbnails) LazyVerticalGrid(
                                columns = GridCells.Adaptive(TileMinWidth),
                                contentPadding = padding,
                                verticalArrangement = Arrangement.spacedBy(TileGap),
                                horizontalArrangement = Arrangement.spacedBy(TileGap),
                            ) {
                                val wide: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
                                if (searching && shown.isNotEmpty()) {
                                    item(key = "here-header", span = wide) { SectionHeader("In ${folderName(state.path)}") }
                                }
                                items(shown, key = { it.path }, contentType = { "tile" }) { entry ->
                                    FileTile(
                                        entry,
                                        thumbnailUrl = if (hasThumbnail(entry)) viewModel.fileUrl(entry) else null,
                                        onClick = { openEntry(entry) },
                                        onAction = { onAction(entry, it) },
                                        selecting = selecting,
                                        selected = entry.path in selected,
                                        onSelect = { toggle(entry) },
                                    )
                                }
                                if (searching) {
                                    item(key = "elsewhere-header", span = wide) { SectionHeader("Everywhere else") }
                                    item(key = "elsewhere-status", span = wide) {
                                        SearchStatus(
                                            index, elsewhere, nothingHere = shown.isEmpty(), query = q,
                                            error = state.searchError, onRetry = viewModel::ensureIndex,
                                        )
                                    }
                                    items(elsewhere.entries, key = { "elsewhere:" + it.path }, contentType = { "tile" }) { entry ->
                                        FileTile(
                                            entry,
                                            thumbnailUrl = if (hasThumbnail(entry)) viewModel.fileUrl(entry) else null,
                                            location = locationOf(entry),
                                            onClick = { openEntry(entry) },
                                            onAction = { onAction(entry, it) },
                                            selecting = selecting,
                                            selected = entry.path in selected,
                                            onSelect = { toggle(entry) },
                                        )
                                    }
                                }
                            } else LazyColumn(
                                contentPadding = padding,
                                verticalArrangement = Arrangement.spacedBy(GroupGap),
                            ) {
                                if (searching && shown.isNotEmpty()) {
                                    item(key = "here-header") { SectionHeader("In ${folderName(state.path)}", Modifier.padding(bottom = 6.dp)) }
                                }
                                itemsIndexed(shown, key = { _, it -> it.path }) { i, entry ->
                                    FileRow(
                                        entry,
                                        groupShape(i, shown.size),
                                        onClick = { openEntry(entry) },
                                        onAction = { onAction(entry, it) },
                                        selecting = selecting,
                                        selected = entry.path in selected,
                                        onSelect = { toggle(entry) },
                                    )
                                }
                                if (searching) {
                                    item(key = "elsewhere-header") { SectionHeader("Everywhere else", Modifier.padding(top = 8.dp, bottom = 6.dp)) }
                                    item(key = "elsewhere-status") {
                                        SearchStatus(
                                            index, elsewhere, nothingHere = shown.isEmpty(), query = q,
                                            error = state.searchError, onRetry = viewModel::ensureIndex,
                                        )
                                    }
                                    itemsIndexed(elsewhere.entries, key = { _, it -> "elsewhere:" + it.path }) { i, entry ->
                                        FileRow(
                                            entry,
                                            groupShape(i, elsewhere.entries.size),
                                            location = locationOf(entry),
                                            onClick = { openEntry(entry) },
                                            onAction = { onAction(entry, it) },
                                            selecting = selecting,
                                            selected = entry.path in selected,
                                            onSelect = { toggle(entry) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selecting) {
            SelectionActions(
                onCopy = { viewModel.cut(takeSelection(), move = false) },
                onMove = { viewModel.cut(takeSelection(), move = true) },
                onDelete = { dialog = FileDialog.Delete(selected.values.toList().withoutNested()) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 20.dp),
            )
        } else Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
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
            title = { Text("Delete ${d.entries.singleOrNull()?.name ?: plural(d.entries.size, "item")}?") },
            text = { Text(deleteWarning(d.entries)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(d.entries)
                    selected = emptyMap()
                    dialog = null
                }) {
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

/** What deleting [entries] does, spelled out before the user confirms. */
private fun deleteWarning(entries: List<FileEntry>): String {
    val folders = entries.count { it.isDir }
    val files = entries.size - folders
    val what = when {
        entries.size == 1 -> if (folders == 1) "The folder and everything in it are" else "The file is"
        folders == 0 -> "${plural(files, "file")} are"
        files == 0 -> "${plural(folders, "folder")} and everything in them are"
        else -> "${plural(files, "file")} and ${plural(folders, "folder")}, with everything in ${if (folders == 1) "it" else "them"}, are"
    }
    return "$what deleted from the server. This can't be undone."
}

/** Picks the folder order (tap the current one again to reverse it) and whether hidden files show. */
@Composable
private fun SortButton(sort: FileSort, onSortBy: (SortBy) -> Unit, onShowHidden: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort and view options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SortBy.entries.forEach { by ->
                val current = by == sort.by
                DropdownMenuItem(
                    text = { Text(by.label, color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                    leadingIcon = { RadioButton(selected = current, onClick = null) },
                    trailingIcon = if (current) {
                        { Text(directionLabel(sort), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = { onSortBy(by) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Show hidden files") },
                leadingIcon = { Checkbox(checked = sort.showHidden, onCheckedChange = null) },
                onClick = { onShowHidden(!sort.showHidden) },
            )
        }
    }
}

/** The current direction in words, e.g. "A–Z" or "Newest first". */
private fun directionLabel(sort: FileSort): String = when (sort.by) {
    SortBy.Name -> if (sort.descending) "Z–A" else "A–Z"
    SortBy.Size -> if (sort.descending) "Largest" else "Smallest"
    SortBy.Date -> if (sort.descending) "Newest" else "Oldest"
}

/** How far the server-wide search has got, or why there's nothing (more) to show. */
@Composable
private fun SearchStatus(
    index: FileIndex?,
    hits: SearchHits,
    nothingHere: Boolean,
    query: String,
    error: String?,
    onRetry: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodyMedium
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (error != null) {
            Text(error, style = style, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
        } else if (index == null || !index.done) {
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
private fun TransferBar(t: Transfer, side: Dp, onCancel: () -> Unit) {
    // The button's own padding stands in for most of the gutter on its side.
    Row(Modifier.padding(start = side, end = (side - 12.dp).coerceAtLeast(0.dp)), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(t.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val p = t.progress
            if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(top = 6.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel") }
    }
}

/** Shown while items are cut or copied: browse to the destination, then paste. */
@Composable
private fun ClipboardBar(clip: Clipboard, side: Dp, onCancel: () -> Unit, onPaste: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = side, vertical = 6.dp).fillMaxWidth(),
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

/** What can be done with the ticked items, floating over the bottom of the list where the + button usually is. */
@Composable
private fun SelectionActions(onCopy: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectionAction("Copy", Icons.Rounded.ContentCopy, onCopy)
            SelectionAction("Move", Icons.AutoMirrored.Rounded.DriveFileMove, onMove)
            SelectionAction("Delete", Icons.Rounded.Delete, onDelete, tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SelectionAction(label: String, icon: ImageVector, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface) {
    TextButton(onClick = onClick) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(label, color = tint)
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
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !last, onClickLabel = "Open $name") { onNavigate(target) }
                    .padding(vertical = 2.dp),
                color = if (last) LocalContentColor.current else MaterialTheme.colorScheme.primary,
            )
            if (!last) Icon(Icons.Rounded.ChevronRight, null, Modifier.padding(horizontal = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

private enum class FileAction(val label: String, val icon: ImageVector, val filesOnly: Boolean = false) {
    Select("Select", Icons.Rounded.CheckCircle),
    OpenWith("Open with…", Icons.AutoMirrored.Rounded.OpenInNew, filesOnly = true),
    Share("Share", Icons.Rounded.Share, filesOnly = true),
    Download("Download", Icons.Rounded.Download, filesOnly = true),
    Rename("Rename", Icons.Rounded.DriveFileRenameOutline),
    Copy("Copy to…", Icons.Rounded.ContentCopy),
    Move("Move to…", Icons.AutoMirrored.Rounded.DriveFileMove),
    Delete("Delete", Icons.Rounded.Delete),
}

/**
 * Tap opens and the ⋮ button shows the item's actions; long-press ticks it, to act on several items together.
 * While [selecting], a checkbox stands in for ⋮ and [onClick] is expected to tick the row instead of opening it.
 */
@Composable
private fun FileRow(
    entry: FileEntry,
    shape: Shape,
    onClick: () -> Unit,
    onAction: (FileAction) -> Unit,
    selecting: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    location: String? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box {
        HearthCard(
            Modifier.fillMaxWidth().then(if (selecting) Modifier.semantics { this.selected = selected } else Modifier),
            shape = shape,
            onClick = onClick,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSelect()
            },
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // Folders carry the accent; files get a neutral tile so folders are easy to pick out.
                if (selected) {
                    IconTile(Icons.Rounded.Check, tint = MaterialTheme.colorScheme.onPrimary, container = MaterialTheme.colorScheme.primary)
                } else if (entry.isDir) {
                    IconTile(iconFor(entry))
                } else {
                    IconTile(
                        iconFor(entry),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                if (selecting) {
                    // The same size as the ⋮ button, so rows don't shift when selecting starts.
                    Box(Modifier.size(48.dp), Alignment.Center) { Checkbox(checked = selected, onCheckedChange = null) }
                } else {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Actions for ${entry.name}") }
                }
            }
        }
        FileMenu(entry, expanded = menu, onDismiss = { menu = false }, onAction = onAction)
    }
}

/** An item's ⋮ menu, the same in the list and in the thumbnail view. */
@Composable
private fun FileMenu(entry: FileEntry, expanded: Boolean, onDismiss: () -> Unit, onAction: (FileAction) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = DpOffset(14.dp, 0.dp)) {
        FileAction.entries.filter { !it.filesOnly || !entry.isDir }.forEach { action ->
            if (action == FileAction.Rename && !entry.isDir) HorizontalDivider()
            if (action == FileAction.Delete) HorizontalDivider()
            val danger = action == FileAction.Delete
            DropdownMenuItem(
                text = { Text(action.label, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
                leadingIcon = { Icon(action.icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { onDismiss(); onAction(action) },
            )
        }
    }
}

/** The narrowest a tile in the thumbnail view gets before the grid drops a column: three across on most phones. */
private val TileMinWidth = 112.dp
private val TileGap = 8.dp
private val TileShape = RoundedCornerShape(20.dp)

/**
 * An item in the thumbnail view: a photo shows itself, anything else its icon. Tap, long-press and ⋮ do what
 * they do on a [FileRow]. ⋮ sits in the corner of the picture so the name gets the tile's whole width, and
 * while [selecting] a tick mark takes its place.
 */
@Composable
private fun FileTile(
    entry: FileEntry,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    onAction: (FileAction) -> Unit,
    selecting: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    location: String? = null,
) {
    var menu by remember { mutableStateOf(false) }
    // Whether the photo is showing, rather than the icon that stands in for it.
    var pictured by remember(thumbnailUrl) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    Box {
        HearthCard(
            Modifier.fillMaxWidth().then(if (selecting) Modifier.semantics { this.selected = selected } else Modifier),
            shape = TileShape,
            onClick = onClick,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSelect()
            },
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                Thumbnail(entry, thumbnailUrl, pictured, onPictured = { pictured = true }, Modifier.matchParentSize())
                if (selected) Box(Modifier.matchParentSize().background(scheme.primary.copy(alpha = 0.35f)))
                // Over a photo, the corner controls are white on a dark spot so they show on any picture.
                val spot = if (pictured) Color.Black.copy(alpha = 0.4f) else Color.Transparent
                if (selecting) {
                    Box(Modifier.align(Alignment.TopEnd).size(TileCorner), Alignment.Center) {
                        if (selected) {
                            Box(Modifier.size(24.dp).background(scheme.primary, CircleShape), Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = scheme.onPrimary)
                            }
                        } else {
                            Box(Modifier.size(24.dp).background(spot, CircleShape).border(2.dp, if (pictured) Color.White else scheme.outline, CircleShape))
                        }
                    }
                } else {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(TileCorner).clip(CircleShape).clickable(role = Role.Button) { menu = true },
                        Alignment.Center,
                    ) {
                        Box(Modifier.size(28.dp).background(spot, CircleShape), Alignment.Center) {
                            Icon(
                                Icons.Rounded.MoreVert, "Actions for ${entry.name}", Modifier.size(20.dp),
                                tint = if (pictured) Color.White else scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // Room for a name of two lines, so the tiles in a row end level whatever the length of their names.
            val nameStyle = MaterialTheme.typography.titleSmall
            val detailStyle = MaterialTheme.typography.labelMedium
            val textHeight = with(LocalDensity.current) { nameStyle.lineHeight.toDp() * 2 + detailStyle.lineHeight.toDp() }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = textHeight)) {
                Text(entry.name, style = nameStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = remember(entry, location) {
                    when {
                        location != null -> "in $location"
                        entry.isDir -> formatModified(entry.modified) ?: "Folder"
                        else -> formatBytes(entry.size)
                    }
                }
                Text(
                    detail,
                    style = detailStyle,
                    color = if (location != null) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FileMenu(entry, expanded = menu, onDismiss = { menu = false }, onAction = onAction)
    }
}

/** The size of the touch target in the corner of a tile's picture. */
private val TileCorner = 44.dp

/**
 * The top of a [FileTile]: the photo at [url], cropped to fill, or the item's icon if it has no picture to
 * show. [onPictured] is called once the photo is on screen.
 */
@Composable
private fun Thumbnail(entry: FileEntry, url: String?, pictured: Boolean, onPictured: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // Folders carry the accent, as in the list.
    val tint = if (entry.isDir) scheme.primary else scheme.onSurfaceVariant
    val container = if (entry.isDir) scheme.primary.copy(alpha = 0.14f) else scheme.surfaceContainerHighest
    Box(modifier.background(container), Alignment.Center) {
        // The icon stands in until the photo arrives, and stays if the photo can't be shown.
        if (!pictured) Icon(iconFor(entry), null, Modifier.size(40.dp), tint = tint)
        if (url != null) {
            val context = LocalContext.current
            val request = remember(url, entry.size, entry.modified) {
                // The size and date are part of what's cached, so a photo replaced on the server isn't shown as the old one.
                val version = "${entry.size}|${entry.modified}"
                ImageRequest.Builder(context)
                    .data(url)
                    .memoryCacheKeyExtra("version", version)
                    .diskCacheKey("$url|$version")
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { onPictured() },
                modifier = Modifier.matchParentSize(),
            )
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
