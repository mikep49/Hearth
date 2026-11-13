package dev.hearth.casaos

import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.FolderListing
import dev.hearth.casaos.data.galleryLooksIn
import dev.hearth.casaos.data.galleryMonths
import dev.hearth.casaos.data.modifiedAt
import dev.hearth.casaos.data.walkFolders
import dev.hearth.casaos.data.without
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

class GalleryTest {
    private fun dir(path: String) = FileEntry(path.substringAfterLast('/'), path, isDir = true, size = 4096, modified = null)
    private fun file(path: String, modified: String?, size: Long = 2_000_000) =
        FileEntry(path.substringAfterLast('/'), path, isDir = false, size = size, modified = modified)

    private val utc: ZoneId = ZoneOffset.UTC

    @Test fun `photos are grouped by month, newest first, with undated ones last`() {
        val entries = listOf(
            file("/DATA/Gallery/a.jpg", "2026-03-04T10:00:00Z"),
            file("/DATA/Gallery/Trips/b.PNG", "2026-11-02T08:00:00Z"),
            file("/DATA/Gallery/c.heic", "2026-11-20T08:00:00Z"),
            file("/DATA/Gallery/z-undated.jpg", null),
            file("/DATA/Gallery/d.webp", "2025-12-31T23:00:00Z"),
            file("/DATA/Gallery/b-undated.jpg", "not a date"),
        )
        val months = galleryMonths(entries, showHidden = false, zone = utc)
        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 3), YearMonth.of(2025, 12), null), months.map { it.month })
        assertEquals(listOf("c.heic", "b.PNG"), months[0].photos.map { it.name })
        assertEquals(listOf("b-undated.jpg", "z-undated.jpg"), months[3].photos.map { it.name })
    }

    @Test fun `only pictures are in the gallery`() {
        val entries = listOf(
            file("/DATA/Gallery/photo.jpg", "2026-03-04T10:00:00Z"),
            file("/DATA/Gallery/clip.mp4", "2026-03-04T10:00:00Z"),
            file("/DATA/Gallery/notes.txt", "2026-03-04T10:00:00Z"),
            file("/DATA/Gallery/empty.jpg", "2026-03-04T10:00:00Z", size = 0),
            // A folder can be named like a photo.
            dir("/DATA/Gallery/holiday.jpg"),
            // Too big for a thumbnail, but still a photo that can be opened.
            file("/DATA/Gallery/panorama.png", "2026-03-05T10:00:00Z", size = 90_000_000),
        )
        assertEquals(listOf("panorama.png", "photo.jpg"), galleryMonths(entries, showHidden = false, zone = utc).flatMap { it.photos }.map { it.name })
    }

    @Test fun `hidden photos show only with hidden files`() {
        val entries = listOf(file("/DATA/Gallery/a.jpg", "2026-03-04T10:00:00Z"), file("/DATA/Gallery/.trashed-a.jpg", "2026-03-04T11:00:00Z"))
        assertEquals(1, galleryMonths(entries, showHidden = false, zone = utc).single().photos.size)
        assertEquals(2, galleryMonths(entries, showHidden = true, zone = utc).single().photos.size)
    }

    @Test fun `the month follows the phone's time zone`() {
        val entries = listOf(file("/DATA/Gallery/new-year.jpg", "2025-12-31T23:30:00Z"))
        assertEquals(YearMonth.of(2025, 12), galleryMonths(entries, false, utc).single().month)
        assertEquals(YearMonth.of(2026, 1), galleryMonths(entries, false, ZoneId.of("Europe/Berlin")).single().month)
    }

    @Test fun `dates read with or without a time zone offset`() {
        assertEquals(Instant.parse("2026-03-04T10:00:00Z"), modifiedAt("2026-03-04T10:00:00Z"))
        assertEquals(Instant.parse("2026-03-04T02:00:00.5Z"), modifiedAt("2026-03-04T10:00:00.5+08:00"))
        assertNull(modifiedAt(null))
        assertNull(modifiedAt(""))
    }

    @Test fun `deleted photos leave the gallery, and a month left empty goes too`() {
        val entries = listOf(
            file("/DATA/Gallery/a.jpg", "2026-03-04T10:00:00Z"),
            file("/DATA/Gallery/b.jpg", "2026-03-05T10:00:00Z"),
            file("/DATA/Gallery/c.jpg", "2026-04-05T10:00:00Z"),
        )
        val left = galleryMonths(entries, false, utc).without(setOf("/DATA/Gallery/c.jpg", "/DATA/Gallery/a.jpg"))
        assertEquals(listOf(YearMonth.of(2026, 3)), left.map { it.month })
        assertEquals(listOf("b.jpg"), left.single().photos.map { it.name })
    }

    @Test fun `the gallery stays out of hidden folders and the apps' folder at the top`() {
        assertTrue(galleryLooksIn(dir("/DATA/Gallery"), "/DATA", showHidden = false))
        assertFalse(galleryLooksIn(dir("/DATA/Gallery/.thumbnails"), "/DATA", showHidden = false))
        assertTrue(galleryLooksIn(dir("/DATA/Gallery/.thumbnails"), "/DATA", showHidden = true))
        assertFalse(galleryLooksIn(dir("/DATA/AppData"), "/DATA", showHidden = true))
        // Only the one at the top of Files is the apps' folder.
        assertTrue(galleryLooksIn(dir("/DATA/Backups/AppData"), "/DATA", showHidden = false))
        assertTrue(galleryLooksIn(dir("/DATA/AppData/immich"), "/DATA", showHidden = false))
    }

    @Test fun `a walk doesn't list what's inside a folder it's told to stay out of`() = runTest {
        val tree = mapOf(
            "/DATA" to listOf(dir("/DATA/AppData"), dir("/DATA/Gallery")),
            "/DATA/AppData" to listOf(file("/DATA/AppData/poster.jpg", null)),
            "/DATA/Gallery" to listOf(file("/DATA/Gallery/a.jpg", null)),
        )
        val index = walkFolders(
            "/DATA",
            { path -> tree[path]?.let { FolderListing(path, it) } ?: throw CasaException.Server(404, "Not found") },
            descend = { galleryLooksIn(it, "/DATA", showHidden = false) },
        )
        assertEquals(setOf("/DATA/AppData", "/DATA/Gallery", "/DATA/Gallery/a.jpg"), index.entries.map { it.path }.toSet())
        assertEquals(2, index.foldersScanned)
    }
}
