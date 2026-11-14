package dev.hearth.casaos.data

import java.time.Instant
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId

/** The folder under the top of Files where CasaOS apps keep their own files. */
private const val APP_DATA = "AppData"

/** One month of the gallery, newest photo first. [month] is null for photos the server gave no date for. */
data class GalleryMonth(val month: YearMonth?, val photos: List<FileEntry>)

/** The gallery: every photo in the folder at [root] and the folders inside it. */
data class GalleryState(
    val root: String,
    val months: List<GalleryMonth> = emptyList(),
    /** True while the folders are being read, the first time or for a refresh. */
    val scanning: Boolean = true,
    val foldersScanned: Int = 0,
    /** True when there were too many files to look through them all. */
    val truncated: Boolean = false,
    val error: String? = null,
) {
    /** All the photos in the order they show, which is also the order the full-screen view swipes through. */
    val photos: List<FileEntry> = months.flatMap { it.photos }
}

/** Whether [entry] is a picture the gallery shows. */
fun isPhoto(entry: FileEntry): Boolean = !entry.isDir && entry.size > 0 && previewKind(entry.name) == PreviewKind.Image

/**
 * Whether the gallery looks for photos inside [dir]. It stays out of hidden folders unless they're shown, and
 * out of the apps' own folder at the [top] of Files: apps keep thousands of covers, icons and previews there,
 * which would bury the user's photos. Opening the gallery from inside that folder still shows what's in it.
 */
fun galleryLooksIn(dir: FileEntry, top: String, showHidden: Boolean): Boolean =
    looksIn(dir.name, dir.path.trimEnd('/'), appsFolder(top), showHidden)

private fun appsFolder(top: String) = top.trimEnd('/') + "/" + APP_DATA

/** [galleryLooksIn], for the folder at [path] called [name]. [apps] is the apps' folder. */
private fun looksIn(name: String, path: String, apps: String, showHidden: Boolean) = (showHidden || !isHidden(name)) && path != apps

/**
 * Whether [index] has everything under the folder at [dir] in it: its walk finished, without stopping early, and
 * started there or in a folder above. It must have gone into every folder, as the search index does.
 */
fun FileIndex.covers(dir: String): Boolean {
    if (!done || truncated) return false
    val base = root.trimEnd('/')
    val d = dir.trimEnd('/')
    return d == base || d.startsWith("$base/")
}

/**
 * The photos the gallery finds under [dir], picked out of [this] index instead of walking the folders again: the
 * ones a walk from [dir] that stays out of what [galleryLooksIn] turns down would reach. Null if the index
 * doesn't [cover][covers] [dir].
 */
fun FileIndex.galleryPhotos(dir: String, top: String, showHidden: Boolean): List<FileEntry>? {
    if (!covers(dir)) return null
    val d = dir.trimEnd('/')
    val inside = "$d/"
    val apps = appsFolder(top)
    return entries.filter { e ->
        if (!isPhoto(e) || !e.path.startsWith(inside)) return@filter false
        // Every folder between [dir] and the photo has to be one the gallery looks in. [dir] itself always is.
        var end = e.path.lastIndexOf('/')
        while (end > d.length) {
            val start = e.path.lastIndexOf('/', end - 1) + 1
            if (!looksIn(e.path.substring(start, end), e.path.substring(0, end), apps, showHidden)) return@filter false
            end = start - 1
        }
        true
    }
}

/** A file's modified time as the server sends it, with or without a time zone offset. */
fun modifiedAt(iso: String?): Instant? = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

/**
 * The photos among [entries] by the month each was last changed, newest first: CasaOS doesn't read the date
 * a photo was taken. Photos without a date come last.
 */
fun galleryMonths(entries: List<FileEntry>, showHidden: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<GalleryMonth> {
    val dated = entries
        .filter { isPhoto(it) && (showHidden || !isHidden(it.name)) }
        .map { it to modifiedAt(it.modified) }
        .sortedWith(compareByDescending<Pair<FileEntry, Instant?>, Instant?>(nullsFirst<Instant>()) { it.second }.thenBy { it.first.path })
    val months = ArrayList<GalleryMonth>()
    var month: YearMonth? = null
    var photos = ArrayList<FileEntry>()
    for ((entry, at) in dated) {
        val m = at?.let { YearMonth.from(it.atZone(zone)) }
        if (m != month && photos.isNotEmpty()) {
            months += GalleryMonth(month, photos)
            photos = ArrayList()
        }
        month = m
        photos += entry
    }
    if (photos.isNotEmpty()) months += GalleryMonth(month, photos)
    return months
}

/** Whether [path] is one of [paths] or inside a folder that is. [paths] don't end in '/', so "/" is "". */
internal fun atOrUnder(path: String, paths: Set<String>): Boolean {
    if (path in paths) return true
    var end = path.lastIndexOf('/')
    while (end >= 0) {
        if (path.substring(0, end) in paths) return true
        end = path.lastIndexOf('/', end - 1)
    }
    return false
}

/**
 * Drops the photos at [paths], and those in folders at [paths], after they're deleted on the server, and any
 * month left empty.
 */
fun List<GalleryMonth>.without(paths: Set<String>): List<GalleryMonth> {
    val gone = paths.mapTo(HashSet()) { it.trimEnd('/') }
    return mapNotNull { m -> m.copy(photos = m.photos.filterNot { atOrUnder(it.path, gone) }).takeIf { it.photos.isNotEmpty() } }
}

/**
 * Follows a rename of [oldPath] to [newPath], a photo or a folder with photos in it. The photos keep their
 * places: renaming doesn't change when a file was last changed.
 */
fun List<GalleryMonth>.renamed(oldPath: String, newPath: String): List<GalleryMonth> =
    map { m -> m.copy(photos = m.photos.renamed(oldPath, newPath)) }
