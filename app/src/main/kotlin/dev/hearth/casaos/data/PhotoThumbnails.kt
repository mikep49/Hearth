package dev.hearth.casaos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

// CasaOS doesn't make thumbnails, so Hearth makes its own from the photos. Most cameras save a small preview
// of each JPEG in its first few KB, so reading just that much is usually enough; the whole photo is fetched
// only when there's no preview or it's too small for the tile, and only when [ThumbnailDownload] allows.

/** How much of a photo Hearth may download to draw its thumbnail. */
enum class ThumbnailDownload(val label: String) {
    /** None of it: photos keep their icons, unless a thumbnail was made for them earlier. */
    Off("Off"),

    /** The start of a JPEG, for the small preview most cameras save there. Other photos keep their icons. */
    Preview("Small"),

    /** The whole photo, when its preview is missing or too small for the tile. */
    Whole("Full"),
    ;

    companion object {
        /** On Wi-Fi, thumbnails may download whole photos; on mobile data, what [onMobileData] (a setting) allows. */
        fun allowed(metered: Boolean, onMobileData: ThumbnailDownload): ThumbnailDownload = if (metered) onMobileData else Whole
    }
}

/**
 * How much of a JPEG is read for its preview. The preview is in the EXIF block, which comes first and holds at
 * most 64 KB, so this covers it with room for the short block some cameras put before it.
 */
const val PHOTO_HEAD_BYTES = 72 * 1024

/** Whether [name] is a JPEG, the kind of photo that usually carries a preview of itself. */
fun mayHavePreview(name: String): Boolean = name.substringAfterLast('.', "").lowercase().let { it == "jpg" || it == "jpeg" }

/**
 * Whether a thumbnail can be made for [entry], a photo: from its preview, whatever its size, or else from the
 * whole photo, up to [THUMBNAIL_MAX_BYTES].
 */
fun thumbnailPossible(entry: FileEntry): Boolean =
    !entry.isDir && entry.size > 0 && (entry.size <= THUMBNAIL_MAX_BYTES || mayHavePreview(entry.name))

// region reading a JPEG's start

/** The preview a camera saved inside a photo: a JPEG of [length] bytes from [offset], [width] × [height] pixels. */
class EmbeddedPreview(val offset: Int, val length: Int, val width: Int, val height: Int)

/**
 * What the start of a JPEG says: its size in pixels as stored, before [orientation] (EXIF's, 1 to 8) turns it
 * upright, or 0 if that's further in than was read; and the preview inside it, if it has one and all of it was read.
 */
class JpegHead(val width: Int, val height: Int, val orientation: Int, val preview: EmbeddedPreview?)

/** Reads the start of a JPEG, or returns null if [bytes] doesn't start one. */
fun readJpegHead(bytes: ByteArray): JpegHead? {
    if (!startsJpeg(bytes, 0)) return null
    var size: Pair<Int, Int>? = null
    var exif: Exif? = null
    forEachSegment(bytes, 2, bytes.size) { marker, start, end ->
        when {
            marker == 0xE1 && exif == null && isExif(bytes, start, end) -> exif = readExif(bytes, start + 6, minOf(end, bytes.size))
            isFrame(marker) -> size = frameSize(bytes, start, minOf(end, bytes.size))
        }
        // The frame header, with the size, comes after the EXIF block: nothing needed lies past it.
        size == null
    }
    val e = exif
    val preview = e?.takeIf { it.previewAt >= 0 && startsJpeg(bytes, it.previewAt) }?.let { p ->
        var previewSize: Pair<Int, Int>? = null
        forEachSegment(bytes, p.previewAt + 2, p.previewAt + p.previewLength) { marker, start, end ->
            if (isFrame(marker)) previewSize = frameSize(bytes, start, minOf(end, p.previewAt + p.previewLength))
            previewSize == null
        }
        previewSize?.let { (w, h) -> EmbeddedPreview(p.previewAt, p.previewLength, w, h) }
    }
    val (width, height) = size ?: e?.let { it.width to it.height }?.takeIf { (w, h) -> w > 0 && h > 0 } ?: (0 to 0)
    return JpegHead(width, height, e?.orientation ?: 1, preview)
}

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

private fun startsJpeg(b: ByteArray, at: Int) = at >= 0 && at + 2 <= b.size && b.u8(at) == 0xFF && b.u8(at + 1) == 0xD8

/** A start-of-frame marker, whose segment gives the picture's size. C4, C8 and CC are other things. */
private fun isFrame(marker: Int) = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

/** A frame header's width and height: after one byte of precision, the height and then the width. */
private fun frameSize(b: ByteArray, start: Int, end: Int): Pair<Int, Int>? {
    if (start + 5 > end) return null
    val height = b.u16be(start + 1)
    val width = b.u16be(start + 3)
    return if (width > 0 && height > 0) width to height else null
}

/**
 * Calls [segment] with each marker segment of a JPEG in [b], from [from] (just past its start marker) until the
 * image data begins, [until] is reached, or [segment] returns false. A segment's contents run from start to
 * end, and end can lie past [until] when only part of it was read.
 */
private inline fun forEachSegment(b: ByteArray, from: Int, until: Int, segment: (marker: Int, start: Int, end: Int) -> Boolean) {
    val limit = minOf(until, b.size)
    var pos = from
    while (pos + 4 <= limit) {
        if (b.u8(pos) != 0xFF) return
        val marker = b.u8(pos + 1)
        when (marker) {
            0xFF -> pos++ // padding before a marker
            0x01, 0xD8, in 0xD0..0xD7 -> pos += 2 // markers with nothing after them
            0xD9, 0xDA -> return // the end, or the image data
            else -> {
                val length = b.u16be(pos + 2)
                if (length < 2) return
                if (!segment(marker, pos + 4, pos + 2 + length)) return
                pos += 2 + length
            }
        }
    }
}

private fun isExif(b: ByteArray, start: Int, end: Int): Boolean =
    start + 6 <= minOf(end, b.size) && "Exif".indices.all { b[start + it] == "Exif"[it].code.toByte() } &&
        b[start + 4] == 0.toByte() && b[start + 5] == 0.toByte()

/** What Hearth uses from a JPEG's EXIF block. [previewAt] is where the preview starts in the file, or -1. */
private class Exif(val orientation: Int, val width: Int, val height: Int, val previewAt: Int, val previewLength: Int)

/**
 * Reads the EXIF block (a TIFF file) that starts at [tiff] and ends at [end]: the orientation and size from its
 * first directory and the one it points to, and the preview, which the second directory points to.
 */
private fun readExif(b: ByteArray, tiff: Int, end: Int): Exif? {
    if (tiff + 8 > end) return null
    val little = when {
        b[tiff] == 'I'.code.toByte() && b[tiff + 1] == 'I'.code.toByte() -> true
        b[tiff] == 'M'.code.toByte() && b[tiff + 1] == 'M'.code.toByte() -> false
        else -> return null
    }
    fun u16(at: Long): Int {
        if (at < tiff || at + 2 > end) return -1
        val i = at.toInt()
        return if (little) b.u8(i) or (b.u8(i + 1) shl 8) else b.u16be(i)
    }
    fun u32(at: Long): Long {
        if (at < tiff || at + 4 > end) return -1
        val i = at.toInt()
        val bytes = (0..3).map { b.u8(i + it).toLong() }
        val (b0, b1, b2, b3) = if (little) bytes.reversed() else bytes
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }
    if (u16(tiff + 2L) != 42) return null

    // A directory is a count, that many 12-byte entries (a tag, a type, a count, and the value itself when it
    // fits in 4 bytes, as the ones read here do), then the offset of the next directory. Offsets count from [tiff].
    fun directory(offset: Long, entry: (tag: Int, value: Long) -> Unit): Long {
        if (offset <= 0) return -1
        val at = tiff + offset
        val count = u16(at)
        if (count < 0) return -1
        for (i in 0 until count) {
            val e = at + 2 + i * 12L
            if (e + 12 > end) return -1
            val value = when (u16(e + 2)) {
                3 -> u16(e + 8).toLong() // SHORT
                4 -> u32(e + 8) // LONG
                else -> -1
            }
            entry(u16(e), value)
        }
        return u32(at + 2 + count * 12L)
    }

    var orientation = 1
    var details = -1L
    var width = 0
    var height = 0
    var previewOffset = -1L
    var previewLength = -1L
    val second = directory(u32(tiff + 4L)) { tag, value ->
        when (tag) {
            0x0112 -> orientation = value.toInt()
            0x8769 -> details = value // the Exif directory, with the picture's size
        }
    }
    directory(details) { tag, value ->
        when (tag) {
            0xA002 -> width = value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            0xA003 -> height = value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }
    }
    directory(second) { tag, value ->
        when (tag) {
            0x0201 -> previewOffset = value
            0x0202 -> previewLength = value
        }
    }
    val previewFits = previewOffset > 0 && previewLength > 0 && tiff + previewOffset + previewLength <= end
    return Exif(
        orientation = orientation.takeIf { it in 1..8 } ?: 1,
        width = width,
        height = height,
        previewAt = if (previewFits) (tiff + previewOffset).toInt() else -1,
        previewLength = if (previewFits) previewLength.toInt() else 0,
    )
}

// endregion

// region choosing what to draw from

/** The part of a preview to show: [width] × [height] pixels from ([x], [y]). */
data class PreviewCrop(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The part of [head]'s preview that matches the photo: all of it, or the middle when the camera padded it with
 * black bars to make it 4:3. Null when there's no preview, or it's the wrong way round for the photo, as one is
 * when an app turned the photo and left the old preview in.
 */
fun previewCrop(head: JpegHead): PreviewCrop? {
    val p = head.preview ?: return null
    val all = PreviewCrop(0, 0, p.width, p.height)
    if (head.width <= 0 || head.height <= 0) return all
    val photo = head.width.toDouble() / head.height
    val shown = p.width.toDouble() / p.height
    if (photo > 1.1 && shown < 1 / 1.1 || photo < 1 / 1.1 && shown > 1.1) return null
    return when {
        shown > photo * 1.02 -> {
            val w = (p.height * photo).roundToInt().coerceIn(1, p.width)
            PreviewCrop((p.width - w) / 2, 0, w, p.height)
        }
        shown < photo / 1.02 -> {
            val h = (p.width / photo).roundToInt().coerceIn(1, p.height)
            PreviewCrop(0, (p.height - h) / 2, p.width, h)
        }
        else -> all
    }
}

/** Whether a picture [width] × [height] pixels looks sharp enough cropped to a square tile [want] pixels across. */
fun sharpEnough(width: Int, height: Int, want: Int): Boolean = minOf(width, height) * 4 >= want * 3

/** What a photo's thumbnail is drawn from. */
sealed interface ThumbnailSource {
    /** The preview inside a JPEG: [jpeg] holds just the preview, [crop] is the part to show, [orientation] is the photo's. */
    class Preview(val jpeg: ByteArray, val crop: PreviewCrop, val orientation: Int) : ThumbnailSource

    /** The whole photo, downloaded to [file]. */
    class Photo(val file: File) : ThumbnailSource
}

/**
 * Fetches what the thumbnail of [entry], at [url], is drawn from, for a tile [want] pixels across. It downloads
 * no more than [download] allows, and a whole photo only up to [THUMBNAIL_MAX_BYTES]. A JPEG's preview is used
 * when it's sharp enough, or when it's all that may be downloaded. A whole photo goes to [photoFile], which the
 * caller deletes. Returns null when there's nothing to draw it from.
 */
suspend fun fetchThumbnailSource(
    http: OkHttpClient,
    url: String,
    entry: FileEntry,
    want: Int,
    download: ThumbnailDownload,
    photoFile: File,
): ThumbnailSource? {
    if (download == ThumbnailDownload.Off || !thumbnailPossible(entry)) return null
    val whole = download == ThumbnailDownload.Whole && entry.size <= THUMBNAIL_MAX_BYTES
    var preview: ThumbnailSource.Preview? = null
    if (mayHavePreview(entry.name) || entry.size <= PHOTO_HEAD_BYTES) {
        val start = http.readStart(url, PHOTO_HEAD_BYTES)
        if (start.isEmpty()) return null
        // Less than was asked for is the whole file.
        if (start.size < PHOTO_HEAD_BYTES) {
            photoFile.writeBytes(start)
            return ThumbnailSource.Photo(photoFile)
        }
        val head = readJpegHead(start)
        val p = head?.preview
        val crop = head?.let(::previewCrop)
        if (p != null && crop != null) {
            preview = ThumbnailSource.Preview(start.copyOfRange(p.offset, p.offset + p.length), crop, head.orientation)
            if (!whole || sharpEnough(crop.width, crop.height, want)) return preview
        }
    }
    if (!whole) return preview
    try {
        photoFile.outputStream().use { http.read(Request.Builder().url(url).build(), it) }
    } catch (e: IOException) {
        // A blurry thumbnail is better than none.
        return preview ?: throw e
    }
    return ThumbnailSource.Photo(photoFile)
}

// endregion

// region network

/** Up to [max] bytes from the start of the file at [url], asking the server for just those. */
suspend fun OkHttpClient.readStart(url: String, max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    read(Request.Builder().url(url).header("Range", "bytes=0-${max - 1}").build(), out, max.toLong())
    return out.toByteArray()
}

/**
 * Copies the reply to [request] into [sink], stopping after [max] bytes, and returns how many it copied. A
 * server that ignores a range sends the whole file, which [max] cuts short. Stops when the caller is cancelled,
 * so a thumbnail scrolled away stops downloading.
 */
private suspend fun OkHttpClient.read(request: Request, sink: OutputStream, max: Long = Long.MAX_VALUE): Long =
    withContext(Dispatchers.IO) {
        newCall(request).await().use { r ->
            if (r.code == 416) return@withContext 0L // an empty file has no first byte to ask for
            if (!r.isSuccessful) throw IOException("The server answered ${r.code}")
            var copied = 0L
            val buf = ByteArray(64 * 1024)
            r.body.byteStream().use { input ->
                while (copied < max) {
                    ensureActive()
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), max - copied).toInt())
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    copied += n
                }
            }
            copied
        }
    }

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
    })
}

// endregion
