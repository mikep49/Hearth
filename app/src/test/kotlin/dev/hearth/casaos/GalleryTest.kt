package dev.hearth.casaos

import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.FileIndex
import dev.hearth.casaos.data.FolderListing
import dev.hearth.casaos.data.atOrUnder
import dev.hearth.casaos.data.covers
import dev.hearth.casaos.data.galleryLooksIn
import dev.hearth.casaos.data.galleryMonths
import dev.hearth.casaos.data.galleryPhotos
import dev.hearth.casaos.data.modifiedAt
import dev.hearth.casaos.data.renamed
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

    private val server = FileIndex(
        "/DATA",
        listOf(
            dir("/DATA/Gallery"), file("/DATA/Gallery/a.jpg", null), file("/DATA/Gallery/notes.txt", null),
            dir("/DATA/Gallery/Trips"), file("/DATA/Gallery/Trips/b.jpg", null),
            dir("/DATA/Gallery/.thumbnails"), file("/DATA/Gallery/.thumbnails/a.jpg", null),
            dir("/DATA/Gallery/.private"), dir("/DATA/Gallery/.private/Old"), file("/DATA/Gallery/.private/Old/c.jpg", null),
            dir("/DATA/AppData"), dir("/DATA/AppData/jellyfin"), file("/DATA/AppData/jellyfin/poster.jpg", null),
            dir("/DATA/Gallery2"), file("/DATA/Gallery2/d.jpg", null),
        ),
        foldersScanned = 9,
        done = true,
    )

    private fun List<FileEntry>?.paths() = this?.map { it.path }?.toSet()

    @Test fun `the gallery picks the photos out of the search index that a walk would find`() {
        assertEquals(setOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg"), server.galleryPhotos("/DATA/Gallery", "/DATA", showHidden = false).paths())
        assertEquals(
            setOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg", "/DATA/Gallery/.thumbnails/a.jpg", "/DATA/Gallery/.private/Old/c.jpg"),
            server.galleryPhotos("/DATA/Gallery", "/DATA", showHidden = true).paths(),
        )
        // From the top it stays out of the apps' folder, as the walk does, but opened inside it shows what's there.
        assertEquals(setOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg", "/DATA/Gallery2/d.jpg"), server.galleryPhotos("/DATA", "/DATA", showHidden = false).paths())
        assertEquals(setOf("/DATA/AppData/jellyfin/poster.jpg"), server.galleryPhotos("/DATA/AppData", "/DATA", showHidden = false).paths())
        // A hidden folder opened from inside: what's in it shows, a hidden folder below it still doesn't.
        assertEquals(setOf("/DATA/Gallery/.private/Old/c.jpg"), server.galleryPhotos("/DATA/Gallery/.private", "/DATA", showHidden = false).paths())
    }

    @Test fun `only a finished, whole index stands in for a walk`() {
        assertTrue(server.covers("/DATA"))
        assertTrue(server.covers("/DATA/Gallery/"))
        assertFalse(server.copy(done = false).covers("/DATA/Gallery"))
        assertFalse(server.copy(truncated = true).covers("/DATA/Gallery"))
        assertFalse(server.covers("/media/USB"))
        assertFalse(server.copy(root = "/DATA/Gallery").covers("/DATA/Gallery2"))
        assertNull(server.copy(truncated = true).galleryPhotos("/DATA/Gallery", "/DATA", showHidden = false))
    }

    @Test fun `deleting a folder takes its photos out of the gallery, but not a folder named like it`() {
        val months = galleryMonths(
            listOf(file("/DATA/Trips/a.jpg", "2026-03-04T10:00:00Z"), file("/DATA/Trips/2025/b.jpg", "2026-03-05T10:00:00Z"), file("/DATA/Trips2/c.jpg", "2026-04-05T10:00:00Z")),
            false, utc,
        )
        assertEquals(listOf("/DATA/Trips2/c.jpg"), months.without(setOf("/DATA/Trips/")).flatMap { it.photos }.map { it.path })
    }

    @Test fun `a rename carries into the gallery, photos keeping their places`() {
        val months = galleryMonths(
            listOf(file("/DATA/Trips/a.jpg", "2026-03-04T10:00:00Z"), file("/DATA/Trips/b.jpg", "2026-03-05T10:00:00Z"), file("/DATA/Other/c.jpg", "2026-04-05T10:00:00Z")),
            false, utc,
        )
        val folder = months.renamed("/DATA/Trips", "/DATA/Holidays").flatMap { it.photos }
        assertEquals(listOf("/DATA/Other/c.jpg", "/DATA/Holidays/b.jpg", "/DATA/Holidays/a.jpg"), folder.map { it.path })
        val photo = months.renamed("/DATA/Trips/a.jpg", "/DATA/Trips/beach.jpg").flatMap { it.photos }.last()
        assertEquals("beach.jpg" to "/DATA/Trips/beach.jpg", photo.name to photo.path)
    }

    @Test fun `a path is at or under the folders given, the top being the empty path`() {
        assertTrue(atOrUnder("/DATA/Trips/a.jpg", setOf("/DATA/Trips")))
        assertTrue(atOrUnder("/DATA/Trips", setOf("/DATA/Trips")))
        assertFalse(atOrUnder("/DATA/Trips2/a.jpg", setOf("/DATA/Trips")))
        assertFalse(atOrUnder("/DATA", setOf("/DATA/Trips")))
        assertTrue(atOrUnder("/DATA/a.jpg", setOf("")))
    }
}
