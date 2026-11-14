package dev.hearth.casaos

import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.JpegHead
import dev.hearth.casaos.data.NetworkLink
import dev.hearth.casaos.data.PHOTO_HEAD_BYTES
import dev.hearth.casaos.data.PreviewCrop
import dev.hearth.casaos.data.THUMBNAIL_MAX_BYTES
import dev.hearth.casaos.data.ThumbnailDownload
import dev.hearth.casaos.data.ThumbnailSource
import dev.hearth.casaos.data.EmbeddedPreview
import dev.hearth.casaos.data.fetchThumbnailSource
import dev.hearth.casaos.data.isMetered
import dev.hearth.casaos.data.previewCrop
import dev.hearth.casaos.data.readJpegHead
import dev.hearth.casaos.data.sharpEnough
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/** Builds JPEGs the way cameras lay them out: an EXIF block with a preview, then the frame header and the picture. */
private object Jpegs {
    private fun u16be(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    fun segment(marker: Int, body: ByteArray) = byteArrayOf(0xFF.toByte(), marker.toByte()) + u16be(body.size + 2) + body
    fun frame(width: Int, height: Int) = segment(0xC0, byteArrayOf(8) + u16be(height) + u16be(width) + byteArrayOf(1, 1, 0x11, 0))
    private val jfif = segment(0xE0, "JFIF".toByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))

    /** A tiny JPEG that's only headers, as an embedded preview. */
    fun preview(width: Int, height: Int) = SOI + frame(width, height) + EOI

    /**
     * An EXIF block (the APP1 segment) with [orientation], the photo's size and [preview], written [little]-endian
     * or not. Each directory holds two entries, so each is 2 + 2 × 12 + 4 = 30 bytes.
     */
    fun exif(orientation: Int, width: Int, height: Int, preview: ByteArray?, little: Boolean = true): ByteArray {
        fun u16(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else u16be(v)
        fun u32(v: Int) = if (little) u16(v and 0xFFFF) + u16(v ushr 16) else u16(v ushr 16) + u16(v and 0xFFFF)
        fun short(tag: Int, v: Int) = u16(tag) + u16(3) + u32(1) + u16(v) + u16(0)
        fun long(tag: Int, v: Int) = u16(tag) + u16(4) + u32(1) + u32(v)
        val ifd0 = 8
        val details = ifd0 + 30
        val ifd1 = details + 30
        val data = ifd1 + 30
        val tiff = (if (little) "II" else "MM").toByteArray() + u16(42) + u32(ifd0) +
            u16(2) + short(0x0112, orientation) + long(0x8769, details) + u32(if (preview != null) ifd1 else 0) +
            u16(2) + long(0xA002, width) + short(0xA003, height) + u32(0) +
            u16(2) + long(0x0201, data) + long(0x0202, preview?.size ?: 0) + u32(0) +
            (preview ?: ByteArray(0))
        return segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + tiff)
    }

    /** A whole photo [bytes] long: the EXIF block, the frame header unless [frame] is false, then noise as the picture. */
    fun photo(exif: ByteArray, width: Int, height: Int, bytes: Int = 300_000, frame: Boolean = true): ByteArray {
        val head = SOI + jfif + exif + (if (frame) frame(width, height) else ByteArray(0)) + byteArrayOf(0xFF.toByte(), 0xDA.toByte())
        return head + Random(1).nextBytes(bytes - head.size - 2) + EOI
    }
}

class PhotoThumbnailsTest {
    @get:Rule val temp = TemporaryFolder()

    private var server: MockWebServer? = null

    @After fun tearDown() {
        server?.shutdown()
    }

    // region reading a JPEG's start

    @Test fun `the preview, orientation and size are read from the start of a photo`() {
        for (little in listOf(true, false)) {
            val preview = Jpegs.preview(512, 384)
            val photo = Jpegs.photo(Jpegs.exif(6, 4000, 3000, preview, little), 4000, 3000)
            val head = readJpegHead(photo.copyOf(PHOTO_HEAD_BYTES))!!
            assertEquals(4000, head.width)
            assertEquals(3000, head.height)
            assertEquals(6, head.orientation)
            val p = head.preview!!
            assertEquals(512 to 384, p.width to p.height)
            assertArrayEquals(preview, photo.copyOfRange(p.offset, p.offset + p.length))
        }
    }

    @Test fun `the size comes from EXIF when the frame header wasn't read`() {
        val head = readJpegHead(Jpegs.photo(Jpegs.exif(1, 4032, 3024, Jpegs.preview(160, 120)), 0, 0, frame = false).copyOf(PHOTO_HEAD_BYTES))!!
        assertEquals(4032 to 3024, head.width to head.height)
        // The frame header is the truth when both are there, since editors can leave EXIF behind.
        val edited = readJpegHead(Jpegs.photo(Jpegs.exif(1, 4032, 3024, Jpegs.preview(160, 120)), 3000, 3000))!!
        assertEquals(3000 to 3000, edited.width to edited.height)
    }

    @Test fun `a photo without a preview, or not a JPEG, has none`() {
        val plain = readJpegHead(Jpegs.photo(Jpegs.exif(1, 800, 600, preview = null), 800, 600))!!
        assertNull(plain.preview)
        assertEquals(800 to 600, plain.width to plain.height)
        assertNull(readJpegHead("\u0089PNG\r\n".toByteArray() + ByteArray(100)))
        assertNull(readJpegHead(ByteArray(0)))
    }

    @Test fun `a preview cut off by the end of what was read is left out`() {
        val photo = Jpegs.photo(Jpegs.exif(3, 4000, 3000, Jpegs.preview(512, 384)), 4000, 3000)
        val whole = readJpegHead(photo)!!.preview!!
        // Read up to the last byte of the preview, but not including it.
        val head = readJpegHead(photo.copyOf(whole.offset + whole.length - 1))!!
        assertNull(head.preview)
        assertEquals(3, head.orientation)
    }

    @Test fun `nonsense in the EXIF block is ignored, not trusted`() {
        // A directory that claims more entries than there's room for, and offsets past the end.
        val exif = Jpegs.segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + "II".toByteArray() + byteArrayOf(42, 0, 8, 0, 0, 0, -1, -1))
        val head = readJpegHead(Jpegs.photo(exif, 640, 480, bytes = 2000))!!
        assertNull(head.preview)
        assertEquals(1, head.orientation)
        assertEquals(640 to 480, head.width to head.height)
    }

    // endregion

    // region choosing

    private fun head(width: Int, height: Int, previewWidth: Int, previewHeight: Int) =
        JpegHead(width, height, 1, EmbeddedPreview(0, 1, previewWidth, previewHeight))

    @Test fun `a preview padded with bars to 4 by 3 is cropped to the photo's shape`() {
        // 3:2 photo, 160×120 preview with bars above and below.
        assertEquals(PreviewCrop(0, 6, 160, 107), previewCrop(head(6000, 4000, 160, 120)))
        // A portrait 16:9 photo, padded at the sides.
        assertEquals(PreviewCrop(14, 0, 68, 120), previewCrop(head(2160, 3840, 96, 120)))
        // Same shape: all of it.
        assertEquals(PreviewCrop(0, 0, 512, 384), previewCrop(head(4000, 3000, 512, 384)))
        // Unknown photo size: all of it.
        assertEquals(PreviewCrop(0, 0, 160, 120), previewCrop(head(0, 0, 160, 120)))
    }

    @Test fun `a preview the wrong way round for the photo isn't used`() {
        // Turned upright by an app that rewrote the pixels and kept the old preview.
        assertNull(previewCrop(head(3000, 4000, 160, 120)))
        assertNull(previewCrop(head(4000, 3000, 120, 160)))
        // A square photo can use either.
        assertNotNull(previewCrop(head(3000, 3000, 160, 120)))
    }

    @Test fun `a preview is sharp enough down to three quarters of the tile`() {
        assertTrue(sharpEnough(512, 384, 318)) // a Files tile on a typical phone
        assertTrue(sharpEnough(320, 240, 320))
        assertFalse(sharpEnough(160, 120, 243)) // the old 160×120 kind, in the gallery
    }

    @Test fun `whole photos are only for Wi-Fi unless Settings allows them`() {
        assertEquals(ThumbnailDownload.Whole, ThumbnailDownload.allowed(metered = false, onMobileData = ThumbnailDownload.Off))
        assertEquals(ThumbnailDownload.Preview, ThumbnailDownload.allowed(metered = true, onMobileData = ThumbnailDownload.Preview))
        assertEquals(ThumbnailDownload.Off, ThumbnailDownload.allowed(metered = true, onMobileData = ThumbnailDownload.Off))
    }

    @Test fun `through a VPN it's the network underneath that's metered or not`() {
        val wifi = NetworkLink(vpn = false, unmetered = true, working = true)
        val mobile = NetworkLink(vpn = false, unmetered = false, working = true)
        // Tailscale calling itself metered.
        val vpn = NetworkLink(vpn = true, unmetered = false, working = true)
        assertFalse(isMetered(wifi, listOf(wifi)))
        assertTrue(isMetered(mobile, listOf(mobile)))
        assertFalse(isMetered(vpn, listOf(vpn, wifi, mobile)))
        assertTrue(isMetered(vpn, listOf(vpn, mobile)))
        // Wi-Fi that doesn't reach the internet doesn't count.
        assertTrue(isMetered(vpn, listOf(vpn, mobile, wifi.copy(working = false))))
        // A VPN with nothing seen under it goes by what it says of itself.
        assertFalse(isMetered(vpn.copy(unmetered = true), listOf(vpn)))
        assertTrue(isMetered(null, emptyList()))
    }

    // endregion

    // region fetching

    /** Serves [file] at any path, honouring a Range header unless [ranges] is false, and failing whole downloads if [wholeFails]. */
    private fun serve(file: ByteArray, ranges: Boolean = true, wholeFails: Boolean = false): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")?.takeIf { ranges }?.removePrefix("bytes=")?.split('-')
                if (range == null) return if (wholeFails) MockResponse().setResponseCode(500) else MockResponse().setBody(Buffer().write(file))
                val from = range[0].toInt()
                val to = (range[1].toIntOrNull() ?: Int.MAX_VALUE).coerceAtMost(file.size - 1)
                return MockResponse().setResponseCode(206).setBody(Buffer().write(file.copyOfRange(from, to + 1)))
            }
        }
        start()
        server = this
    }

    private suspend fun fetch(server: MockWebServer, name: String, size: Int, want: Int, download: ThumbnailDownload): ThumbnailSource? =
        fetchThumbnailSource(
            OkHttpClient(),
            server.url("/v1/file?path=/DATA/$name").toString(),
            FileEntry(name, "/DATA/$name", isDir = false, size = size.toLong(), modified = null),
            want,
            download,
            temp.newFile(),
        )

    private val sharpPhoto = Jpegs.photo(Jpegs.exif(6, 4000, 3000, Jpegs.preview(512, 384)), 4000, 3000)
    private val blurryPhoto = Jpegs.photo(Jpegs.exif(1, 4000, 3000, Jpegs.preview(160, 120)), 4000, 3000)

    @Test fun `a sharp enough preview needs only the start of the photo`() = runTest {
        val s = serve(sharpPhoto)
        val source = fetch(s, "a.jpg", sharpPhoto.size, want = 318, ThumbnailDownload.Whole) as ThumbnailSource.Preview
        assertEquals(6, source.orientation)
        assertEquals(PreviewCrop(0, 0, 512, 384), source.crop)
        assertArrayEquals(Jpegs.preview(512, 384), source.jpeg)
        assertEquals(1, s.requestCount)
        assertEquals("bytes=0-${PHOTO_HEAD_BYTES - 1}", s.takeRequest().getHeader("Range"))
    }

    @Test fun `a blurry preview is swapped for the whole photo when that's allowed`() = runTest {
        val s = serve(blurryPhoto)
        val source = fetch(s, "a.jpg", blurryPhoto.size, want = 318, ThumbnailDownload.Whole) as ThumbnailSource.Photo
        assertArrayEquals(blurryPhoto, source.file.readBytes())
        assertEquals(2, s.requestCount)
        s.takeRequest()
        assertNull(s.takeRequest().getHeader("Range"))
    }

    @Test fun `on mobile data a blurry preview is better than downloading the photo`() = runTest {
        val s = serve(blurryPhoto)
        val source = fetch(s, "a.jpg", blurryPhoto.size, want = 318, ThumbnailDownload.Preview)
        assertTrue(source is ThumbnailSource.Preview)
        assertEquals(1, s.requestCount)
    }

    @Test fun `a photo too big to download whole keeps a blurry preview, and with none keeps its icon`() = runTest {
        val s = serve(blurryPhoto)
        assertTrue(fetch(s, "a.jpg", (THUMBNAIL_MAX_BYTES + 1).toInt(), want = 318, ThumbnailDownload.Whole) is ThumbnailSource.Preview)
        val plain = Jpegs.photo(Jpegs.exif(1, 4000, 3000, preview = null), 4000, 3000)
        val s2 = serve(plain)
        assertNull(fetch(s2, "b.jpg", (THUMBNAIL_MAX_BYTES + 1).toInt(), want = 318, ThumbnailDownload.Whole))
        assertEquals(1, s2.requestCount)
    }

    @Test fun `other photos download nothing on mobile data and whole on Wi-Fi`() = runTest {
        val png = Random(2).nextBytes(200_000)
        val s = serve(png)
        assertNull(fetch(s, "shot.png", png.size, want = 318, ThumbnailDownload.Preview))
        assertEquals(0, s.requestCount)
        val source = fetch(s, "shot.png", png.size, want = 318, ThumbnailDownload.Whole) as ThumbnailSource.Photo
        assertArrayEquals(png, source.file.readBytes())
        assertEquals(1, s.requestCount)
        assertNull(s.takeRequest().getHeader("Range"))
    }

    @Test fun `with thumbnails off nothing is downloaded`() = runTest {
        val s = serve(sharpPhoto)
        assertNull(fetch(s, "a.jpg", sharpPhoto.size, want = 318, ThumbnailDownload.Off))
        assertEquals(0, s.requestCount)
    }

    @Test fun `a small photo is used whole, read in the one request`() = runTest {
        val small = Jpegs.photo(Jpegs.exif(1, 300, 200, Jpegs.preview(160, 120)), 300, 200, bytes = 20_000)
        val s = serve(small)
        val source = fetch(s, "small.jpg", small.size, want = 318, ThumbnailDownload.Preview) as ThumbnailSource.Photo
        assertArrayEquals(small, source.file.readBytes())
        assertEquals(1, s.requestCount)
        // Even a small PNG on mobile data: the request for its start brings all of it.
        val png = Random(3).nextBytes(30_000)
        val s2 = serve(png)
        assertTrue(fetch(s2, "icon.png", png.size, want = 318, ThumbnailDownload.Preview) is ThumbnailSource.Photo)
    }

    @Test fun `a server that ignores the range still only has the start read`() = runTest {
        val s = serve(sharpPhoto, ranges = false)
        assertTrue(fetch(s, "a.jpg", sharpPhoto.size, want = 318, ThumbnailDownload.Preview) is ThumbnailSource.Preview)
        assertEquals(1, s.requestCount)
    }

    @Test fun `when the whole photo can't be had, the preview does`() = runTest {
        val s = serve(blurryPhoto, wholeFails = true)
        assertTrue(fetch(s, "a.jpg", blurryPhoto.size, want = 318, ThumbnailDownload.Whole) is ThumbnailSource.Preview)
        assertEquals(2, s.requestCount)
    }

    // endregion
}
