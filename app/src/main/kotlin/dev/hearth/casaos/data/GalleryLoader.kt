package dev.hearth.casaos.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long the photos found for the gallery show as they are before it looks through the folders again. */
const val GALLERY_FRESH_MS = 10 * 60_000L

/** The most folders whose photos the gallery remembers at once. */
private const val REMEMBERED_FOLDERS = 4

/**
 * The search index, which the gallery can take its photos from instead of walking the folders. [at] is when
 * the walk that built it began, and [upToDate] is false once files may have changed in ways it doesn't show.
 */
data class IndexSnapshot(val index: FileIndex, val at: Long, val upToDate: Boolean)

/**
 * Finds the photos for the gallery, and remembers them so it opens straight away the next time. What it found
 * for the last few folders, or the search index, stands in for walking the folders again. Once that's
 * [GALLERY_FRESH_MS] old, or Hearth has changed files in a way it can't follow, the gallery still shows it
 * straight away, and looks through the folders again in the background.
 */
class GalleryLoader(
    private val scope: CoroutineScope,
    private val list: suspend (String) -> FolderListing,
    /** The top of Files, where the gallery stays out of the apps' folder. */
    private val top: String,
    /** The search index, once a walk of the whole server has built it. */
    private val index: () -> IndexSnapshot?,
    /** Milliseconds on a clock that only goes forward. */
    private val clock: () -> Long,
    /** Told when a refresh fails while photos are showing. */
    private val onError: (String) -> Unit = {},
    /** Sorts photos into months, away from the main thread. */
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    private data class Key(val root: String, val showHidden: Boolean)

    /** The photos found under one folder, and when the look that found them began. */
    private data class Found(
        val months: List<GalleryMonth>,
        val foldersScanned: Int,
        val truncated: Boolean,
        val at: Long,
        /** False once files may have changed in ways [months] doesn't show. */
        val upToDate: Boolean,
    ) {
        val photoCount = months.sumOf { it.photos.size }

        fun freshAt(now: Long) = upToDate && now - at < GALLERY_FRESH_MS

        fun state(root: String, scanning: Boolean) = GalleryState(root, months, scanning, foldersScanned, truncated)
    }

    private val _state = MutableStateFlow<GalleryState?>(null)

    /** The gallery, while it's open. */
    val state: StateFlow<GalleryState?> = _state.asStateFlow()

    /** What was found for each folder, the one opened longest ago first. */
    private val found = LinkedHashMap<Key, Found>(REMEMBERED_FOLDERS * 2, 0.75f, true)
    private var opened: Key? = null

    /** Whatever the open gallery is doing: picking photos out of the search index, or walking the folders. */
    private var job: Job? = null
    private var walk: Job? = null

    /** Counts changes Hearth couldn't follow: one made while a walk runs may be missing from what it finds. */
    private var changes = 0

    /** Photos deleted while the walk under way runs, which it may still find. */
    private var deletedDuringWalk: MutableSet<String>? = null

    /** Shows the photos in the folder at [root] and the folders inside it, looking through them if need be. */
    fun open(root: String, showHidden: Boolean) {
        stop()
        val key = Key(root, showHidden)
        opened = key
        val now = clock()
        val known = found[key]
        if (known != null && known.freshAt(now)) {
            _state.value = known.state(root, scanning = false)
            return
        }
        // A recent search index saves the walk. An older one is still worth showing while it runs, when there's
        // nothing else to.
        val snapshot = index()?.takeIf { s ->
            s.index.covers(root) && (known == null || s.upToDate && now - s.at < GALLERY_FRESH_MS)
        }
        _state.value = known?.state(root, scanning = true) ?: GalleryState(root)
        if (snapshot == null) return look(key)
        job = scope.launch {
            val months = withContext(work) { snapshot.index.galleryPhotos(root, top, showHidden)?.let { galleryMonths(it, showHidden) } }
            if (months != null) {
                val picked = Found(months, foldersScanned = 0, truncated = false, snapshot.at, snapshot.upToDate)
                remember(key, picked)
                if (picked.freshAt(clock())) {
                    _state.value = picked.state(root, scanning = false)
                    return@launch
                }
                _state.value = picked.state(root, scanning = true)
            }
            look(key)
        }
    }

    /** Looks through the open gallery's folders again, unless it's doing so already. */
    fun refresh() {
        val key = opened ?: return
        if (walk?.isActive == true) return
        stop()
        look(key)
    }

    fun close() {
        stop()
        opened = null
        _state.value = null
    }

    /** Drops photos deleted on the server, and those in deleted folders, from what's showing and remembered. */
    fun deleted(paths: Set<String>) {
        deletedDuringWalk?.addAll(paths)
        val gone = paths.mapTo(HashSet()) { it.trimEnd('/') }
        found.entries.removeAll { atOrUnder(it.key.root, gone) }
        for (e in found.entries) e.setValue(e.value.copy(months = e.value.months.without(gone)))
        _state.update { it?.copy(months = it.months.without(gone)) }
    }

    /**
     * Follows a rename on the server of [oldPath], a photo or a folder, to [newPath]. Folders that held it are
     * looked through again the next time the gallery opens: the new name can make it a photo, or a hidden one.
     */
    fun renamed(oldPath: String, newPath: String) {
        changes++
        val old = setOf(oldPath.trimEnd('/'))
        // Photos found in the renamed folder, or below it, are under another path now.
        found.entries.removeAll { atOrUnder(it.key.root, old) }
        for (e in found.entries) {
            if (atOrUnder(oldPath, setOf(e.key.root.trimEnd('/')))) {
                e.setValue(e.value.copy(months = e.value.months.renamed(oldPath, newPath), upToDate = false))
            }
        }
        _state.update { it?.copy(months = it.months.renamed(oldPath, newPath)) }
    }

    /** Files changed in ways the gallery can't follow, such as uploads, copies and moves. */
    fun outdated() {
        changes++
        for (e in found.entries) e.setValue(e.value.copy(upToDate = false))
    }

    private fun stop() {
        job?.cancel()
        walk?.cancel()
        job = null
        walk = null
    }

    /**
     * Walks the folders for photos, keeping what's showing until it's done: photos turn up in folder order, so
     * a list filled in as they arrive would keep shifting.
     */
    private fun look(key: Key) {
        _state.update { it?.copy(scanning = true, error = null) }
        val started = clock()
        val changesBefore = changes
        val deleted = HashSet<String>().also { deletedDuringWalk = it }
        walk = scope.launch {
            try {
                val walked = walkFolders(
                    key.root, list,
                    descend = { galleryLooksIn(it, top, key.showHidden) },
                    // Only counted while there's nothing else to show: with photos up, it would redraw the grid
                    // for a number that isn't on screen.
                    onProgress = { p ->
                        _state.update { s -> if (s == null || s.months.isNotEmpty()) s else s.copy(foldersScanned = p.foldersScanned) }
                    },
                )
                val months = withContext(work) { galleryMonths(walked.entries, key.showHidden) }.without(deleted)
                ensureActive()
                val result = Found(months, walked.foldersScanned, walked.truncated, started, upToDate = changes == changesBefore)
                remember(key, result)
                _state.value = result.state(key.root, scanning = false)
            } catch (e: CasaException) {
                ensureActive()
                _state.update { it?.copy(scanning = false, error = e.message) }
                if (_state.value?.months?.isNotEmpty() == true) onError("Couldn't refresh the gallery: ${e.message}")
            } finally {
                if (deletedDuringWalk === deleted) deletedDuringWalk = null
            }
        }
        job = walk
    }

    /** Keeps [value] for [key], letting go of the folders opened longest ago once there are too many photos. */
    private fun remember(key: Key, value: Found) {
        found[key] = value
        var photos = found.values.sumOf { it.photoCount }
        val oldest = found.entries.iterator()
        while (found.size > 1 && (found.size > REMEMBERED_FOLDERS || photos > INDEX_MAX_ENTRIES)) {
            photos -= oldest.next().value.photoCount
            oldest.remove()
        }
    }
}
