package dev.hearth.casaos.data

import java.time.Instant

enum class SortBy(val label: String) { Name("Name"), Size("Size"), Date("Date modified") }

/** How Files orders a folder, and whether it shows hidden (dot) files. */
data class FileSort(
    val by: SortBy = SortBy.Name,
    val descending: Boolean = false,
    val showHidden: Boolean = false,
) {
    /** Picking the current order again flips it; a new one starts the way people usually want it. */
    fun pick(by: SortBy): FileSort =
        if (by == this.by) copy(descending = !descending)
        // Biggest and newest first are the useful starting points; names start at A.
        else copy(by = by, descending = by != SortBy.Name)
}

/** True for dot files and folders, which Linux file managers hide by default. */
fun isHidden(name: String) = name.startsWith('.')

/** True when [path] is hidden or inside a hidden folder below [root]. */
fun isHiddenPath(path: String, root: String): Boolean =
    path.removePrefix(root).split('/').any { isHidden(it) }

/**
 * The entries to show, in order. Folders always come first. Folders have no meaningful size, so sorting
 * by size keeps them in name order. Ties, and dates the server didn't send, fall back to name order.
 */
fun List<FileEntry>.arranged(sort: FileSort): List<FileEntry> {
    val visible = if (sort.showHidden) this else filterNot { isHidden(it.name) }
    val byName = compareBy<FileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    val order: Comparator<FileEntry> = when (sort.by) {
        SortBy.Name -> if (sort.descending) byName.reversed() else byName
        SortBy.Size -> Comparator<FileEntry> { a, b ->
            val size = if (a.isDir || b.isDir) 0 else a.size.compareTo(b.size)
            if (sort.descending) -size else size
        }.then(byName)
        SortBy.Date -> {
            val times = associateWith { e -> e.modified?.let { runCatching { Instant.parse(it) }.getOrNull() } }
            Comparator<FileEntry> { a, b ->
                val ta = times[a]
                val tb = times[b]
                when {
                    ta == null && tb == null -> 0
                    ta == null -> 1
                    tb == null -> -1
                    sort.descending -> tb.compareTo(ta)
                    else -> ta.compareTo(tb)
                }
            }.then(byName)
        }
    }
    return visible.sortedWith(compareByDescending<FileEntry> { it.isDir }.then(order))
}
