package dev.hearth.casaos.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.THUMBNAIL_MAX_BYTES
import dev.hearth.casaos.data.ThumbnailDownload
import dev.hearth.casaos.data.ThumbnailSource
import dev.hearth.casaos.data.fetchThumbnailSource
import dev.hearth.casaos.data.sharpEnough
import okhttp3.OkHttpClient
import okio.Buffer
import okio.FileSystem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** A thumbnail drawn by [drawThumbnail]. [shorter] is its shorter side; [full] means the photo has no more detail to give. */
class DrawnThumbnail(val bitmap: Bitmap, val shorter: Int, val full: Boolean)

/**
 * Draws a thumbnail from [source]: upright, cropped to the photo's shape, and shrunk to no smaller than [target]
 * pixels on its shorter side. Null if the picture can't be read.
 */
fun drawThumbnail(source: ThumbnailSource, target: Int): DrawnThumbnail? = when (source) {
    is ThumbnailSource.Preview -> {
        val decoded = BitmapFactory.decodeByteArray(source.jpeg, 0, source.jpeg.size)
        decoded?.let {
            val c = source.crop
            val fits = c.x + c.width <= it.width && c.y + c.height <= it.height
            val cropped = if (!fits || c.width == it.width && c.height == it.height) it else Bitmap.createBitmap(it, c.x, c.y, c.width, c.height)
            DrawnThumbnail(upright(cropped, source.orientation), minOf(cropped.width, cropped.height), full = false)
        }
    }
    is ThumbnailSource.Photo -> {
        val path = source.file.path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val shortest = minOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (shortest / (sample * 2) >= target) sample *= 2
        val decoded = if (shortest > 0) BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) else null
        decoded?.let {
            val shorter = minOf(it.width, it.height)
            val scaled = if (shorter > target) {
                it.scale(it.width * target / shorter, it.height * target / shorter)
            } else it
            val orientation = runCatching {
                ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            DrawnThumbnail(upright(scaled, orientation), minOf(scaled.width, scaled.height), full = sample == 1 && scaled === it)
        }
    }
}

/** [bitmap] turned (and flipped, for the rare mirrored orientations) the way EXIF [orientation] says. */
private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
}

/** The thumbnail as a file to keep: PNG keeps transparency, and photos are much smaller as JPEG. */
fun Bitmap.thumbnailBytes(): ByteArray = ByteArrayOutputStream().also {
    compress(if (hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, it)
}.toByteArray()

/** Where whole photos wait while their thumbnails are drawn. Each is deleted once its thumbnail is made. */
fun thumbnailPhotoDir(context: Context) = File(context.cacheDir, "thumbnail-photos")

/** Deletes photos left behind when Android ended Hearth partway through making a thumbnail. */
fun pruneThumbnailPhotos(context: Context, now: Long = System.currentTimeMillis()) {
    thumbnailPhotoDir(context).listFiles().orEmpty().filter { now - it.lastModified() > 60 * 60 * 1000L }.forEach { it.delete() }
}

/**
 * A photo's thumbnail in Files and the gallery, for Coil to load with [PhotoThumbnailFetcher]: the photo at
 * [url], drawn from no more than [download] allows.
 */
data class PhotoThumbnail(val url: String, val entry: FileEntry, val download: ThumbnailDownload) {
    /** Changes when the photo does, so a photo replaced on the server isn't shown as the old one. */
    private val version get() = "$url|${entry.size}|${entry.modified}"

    /**
     * In memory, one made when whole photos may be downloaded is kept apart from one made when they can't, so
     * coming back to Wi-Fi sharpens what mobile data left blurry.
     */
    val memoryKey: String get() = memoryKey(download == ThumbnailDownload.Whole)

    /** The other of the two, to show while this one loads. */
    val otherMemoryKey: String get() = memoryKey(download != ThumbnailDownload.Whole)

    private fun memoryKey(whole: Boolean) = "thumbnail:${if (whole) "whole" else "light"}:$version"

    /** On disk there's one, replaced by a sharper one when it can be. */
    val diskKey: String get() = "thumbnail:$version"
}

/**
 * Makes [PhotoThumbnail]s, and keeps them small in Coil's disk cache: a few tens of KB each, rather than the
 * whole photos, so scrolling back through a big library doesn't download it again.
 */
class PhotoThumbnailFetcher(
    private val thumbnail: PhotoThumbnail,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val http: () -> OkHttpClient,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val size = options.size
        val want = maxOf(size.width.pxOrElse { 0 }, size.height.pxOrElse { 0 }).takeIf { it > 0 } ?: DEFAULT_PX
        val cache = imageLoader.diskCache ?: return save(null, make(want) ?: throw noThumbnail())
        var cached = cache.openSnapshot(thumbnail.diskKey)
        try {
            val made = cached?.let { readMade(cache, it) }
            if (cached != null && made != null && made.enoughFor(want)) return cached.result(cache, DataSource.DISK).also { cached = null }
            val fresh = try {
                make(want)
            } catch (e: IOException) {
                null
            }
            if (fresh == null) {
                // Nothing better can be made now (or may be downloaded): show the one made before, if there is one.
                cached?.let { return it.result(cache, DataSource.DISK).also { cached = null } }
                throw noThumbnail()
            }
            // The cache won't replace an entry while it's open.
            cached?.close()
            cached = null
            return save(cache, fresh)
        } finally {
            cached?.close()
        }
    }

    private fun noThumbnail() = IOException("No thumbnail for ${thumbnail.entry.name}")

    private class Fresh(val bytes: ByteArray, val made: Made)

    /** What a saved thumbnail was made from, kept beside it. */
    private inner class Made(val shorter: Int, val full: Boolean) {
        /** Whether this one will do for a tile [want] pixels across, or a sharper one could be made now. */
        fun enoughFor(want: Int): Boolean =
            full || sharpEnough(shorter, shorter, want) ||
                thumbnail.download != ThumbnailDownload.Whole || thumbnail.entry.size > THUMBNAIL_MAX_BYTES

        override fun toString() = "$shorter ${if (full) "full" else "part"}"
    }

    private fun readMade(cache: DiskCache, snapshot: DiskCache.Snapshot): Made? = runCatching {
        val (shorter, full) = cache.fileSystem.read(snapshot.metadata) { readUtf8() }.trim().split(' ')
        Made(shorter.toInt(), full == "full")
    }.getOrNull()

    private suspend fun make(want: Int): Fresh? {
        if (thumbnail.download == ThumbnailDownload.Off) return null
        val dir = thumbnailPhotoDir(options.context).apply { mkdirs() }
        val photo = File.createTempFile("photo", null, dir)
        try {
            val source = fetchThumbnailSource(http(), thumbnail.url, thumbnail.entry, want, thumbnail.download, photo) ?: return null
            // Made a little bigger than this tile needs, so the same one does for the gallery and Files' bigger tiles.
            val drawn = drawThumbnail(source, maxOf(want, KEPT_PX)) ?: return null
            return Fresh(drawn.bitmap.thumbnailBytes(), Made(drawn.shorter, drawn.full))
        } finally {
            photo.delete()
        }
    }

    private fun save(cache: DiskCache?, fresh: Fresh): FetchResult {
        val editor = cache?.openEditor(thumbnail.diskKey)
        if (editor != null) {
            val written = runCatching {
                cache.fileSystem.write(editor.data) { write(fresh.bytes) }
                cache.fileSystem.write(editor.metadata) { writeUtf8(fresh.made.toString()) }
            }.isSuccess
            if (written) {
                runCatching { editor.commitAndOpenSnapshot() }.getOrNull()?.let { return it.result(cache, DataSource.NETWORK) }
            } else {
                runCatching { editor.abort() }
            }
        }
        return SourceFetchResult(ImageSource(Buffer().write(fresh.bytes), FileSystem.SYSTEM), null, DataSource.NETWORK)
    }

    private fun DiskCache.Snapshot.result(cache: DiskCache, from: DataSource) =
        SourceFetchResult(ImageSource(data, cache.fileSystem, thumbnail.diskKey, closeable = this), null, from)

    class Factory(private val http: () -> OkHttpClient) : Fetcher.Factory<PhotoThumbnail> {
        override fun create(data: PhotoThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            PhotoThumbnailFetcher(data, options, imageLoader, http)
    }

    private companion object {
        /** For a request with no size, which Files and the gallery don't make. */
        const val DEFAULT_PX = 512

        /** The smallest a thumbnail drawn from a whole photo is kept: enough for a tile on a sharp phone screen. */
        const val KEPT_PX = 384
    }
}
