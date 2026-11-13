package dev.hearth.casaos.data

import java.time.Instant
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId

/** The folder under the top of Files where CasaOS apps keep their own files. */
private const val APP_DATA = "AppData"

/** One month of the gallery, newest photo first. [month] is null for photos the server gave no date for. */
data class GalleryMonth(val month: YearMonth?, val photos: List<FileEntry>)

/** Whether [entry] is a picture the gallery shows. */
fun isPhoto(entry: FileEntry): Boolean = !entry.isDir && entry.size > 0 && previewKind(entry.name) == PreviewKind.Image

/**
 * Whether the gallery looks for photos inside [dir]. It stays out of hidden folders unless they're shown, and
 * out of the apps' own folder at the [top] of Files: apps keep thousands of covers, icons and previews there,
 * which would bury the user's photos. Opening the gallery from inside that folder still shows what's in it.
 */
fun galleryLooksIn(dir: FileEntry, top: String, showHidden: Boolean): Boolean =
    (showHidden || !isHidden(dir.name)) && dir.path.trimEnd('/') != top.trimEnd('/') + "/" + APP_DATA

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

/** Drops the photos at [paths], after they're deleted on the server, and any month left empty. */
fun List<GalleryMonth>.without(paths: Set<String>): List<GalleryMonth> =
    mapNotNull { m -> m.copy(photos = m.photos.filterNot { it.path in paths }).takeIf { it.photos.isNotEmpty() } }
