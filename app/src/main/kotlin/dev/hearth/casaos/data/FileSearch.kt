package dev.hearth.casaos.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Every file and folder found under [root], built by walking the server's folders one by one:
 * CasaOS has no search API, so this is the only way to search beyond the open folder.
 */
data class FileIndex(
    val root: String,
    val entries: List<FileEntry>,
    val foldersScanned: Int,
    /** False while the walk is still going. */
    val done: Boolean,
    /** True when the walk stopped early because it reached [INDEX_MAX_ENTRIES]. */
    val truncated: Boolean = false,
)

/** Keeps memory and time bounded on servers with huge trees (container data, photo libraries). */
const val INDEX_MAX_ENTRIES = 100_000

/** Guards against symlink loops, which the folder API follows. */
private const val INDEX_MAX_DEPTH = 40

/** Folders listed at the same time: fast enough, without flooding a small home server. */
private const val INDEX_PARALLEL = 6

private const val PROGRESS_EVERY_MS = 300L

/**
 * Lists every folder under [root], breadth first so the top of the tree is covered first if the walk is cut short.
 * Folders that can't be read (no permission, vanished) are skipped; a lost connection or session ends the walk.
 * A folder [descend] turns down is still listed in the result, but nothing inside it is.
 */
suspend fun walkFolders(
    root: String,
    list: suspend (String) -> FolderListing,
    descend: (FileEntry) -> Boolean = { true },
    onProgress: (FileIndex) -> Unit = {},
): FileIndex = coroutineScope {
    val found = ArrayList<FileEntry>()
    var scanned = 0
    var truncated = false
    var lastReport = System.currentTimeMillis()
    var level = listOf(root)
    var depth = 0
    while (level.isNotEmpty() && !truncated && depth <= INDEX_MAX_DEPTH) {
        val next = ArrayList<String>()
        for (chunk in level.chunked(INDEX_PARALLEL)) {
            val listings = chunk.map { path ->
                async {
                    try {
                        list(path)
                    } catch (e: CasaException.Server) {
                        null
                    } catch (e: CasaException.Parse) {
                        null
                    }
                }
            }.awaitAll()
            for (listing in listings) {
                scanned++
                for (entry in listing?.entries.orEmpty()) {
                    if (found.size >= INDEX_MAX_ENTRIES) {
                        truncated = true
                        break
                    }
                    found += entry
                    if (entry.isDir && descend(entry)) next += entry.path
                }
            }
            if (truncated) break
            val now = System.currentTimeMillis()
            if (now - lastReport >= PROGRESS_EVERY_MS) {
                lastReport = now
                onProgress(FileIndex(root, found.toList(), scanned, done = false))
            }
        }
        level = next
        depth++
    }
    FileIndex(root, found.toList(), scanned, done = true, truncated = truncated)
}

/** Drops [paths] and everything inside them, after they're deleted on the server. */
fun FileIndex.without(paths: Collection<String>): FileIndex {
    val prefixes = paths.map { it.trimEnd('/') + "/" }
    return copy(entries = entries.filter { e -> e.path !in paths && prefixes.none { e.path.startsWith(it) } })
}

/** Leaves out entries inside a folder that's also in the list: copying, moving or deleting the folder covers them. */
fun List<FileEntry>.withoutNested(): List<FileEntry> {
    val folders = filter { it.isDir }.map { it.path.trimEnd('/') + "/" }
    return filter { e -> folders.none { e.path.startsWith(it) } }
}

/** Follows a rename of [oldPath] to [newPath], including everything inside a renamed folder. */
fun FileIndex.renamed(oldPath: String, newPath: String): FileIndex {
    val oldPrefix = oldPath.trimEnd('/') + "/"
    val newName = newPath.trimEnd('/').substringAfterLast('/')
    return copy(
        entries = entries.map { e ->
            when {
                e.path == oldPath -> e.copy(name = newName, path = newPath)
                e.path.startsWith(oldPrefix) -> e.copy(path = newPath.trimEnd('/') + "/" + e.path.removePrefix(oldPrefix))
                else -> e
            }
        },
    )
}

data class SearchHits(val entries: List<FileEntry>, /** All matches, of which [entries] are the best. */ val total: Int)

/**
 * Entries whose name contains [query], ignoring case: exact names first, then names that start with it,
 * folders before files, then shallower paths. Entries directly in [skipDir] are left out (they're shown already).
 */
fun searchFiles(entries: List<FileEntry>, query: String, skipDir: String? = null, limit: Int = 300): SearchHits {
    val q = query.trim()
    if (q.isEmpty()) return SearchHits(emptyList(), 0)
    val skipPrefix = skipDir?.trimEnd('/')?.plus("/")
    val matches = entries.filter { e ->
        e.name.contains(q, ignoreCase = true) &&
            (skipPrefix == null || !(e.path.startsWith(skipPrefix) && '/' !in e.path.substring(skipPrefix.length)))
    }
    val ranked = matches.sortedWith(
        compareBy<FileEntry> {
            when {
                it.name.equals(q, ignoreCase = true) -> 0
                it.name.startsWith(q, ignoreCase = true) -> 1
                else -> 2
            }
        }
            .thenByDescending { it.isDir }
            .thenBy { e -> e.path.count { it == '/' } }
            .thenBy { it.path.lowercase() },
    )
    return SearchHits(ranked.take(limit), matches.size)
}
