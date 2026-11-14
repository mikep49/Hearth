package dev.hearth.casaos

import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.PreviewKind
import dev.hearth.casaos.data.THUMBNAIL_MAX_BYTES
import dev.hearth.casaos.data.hasThumbnail
import dev.hearth.casaos.data.parseStoreApps
import dev.hearth.casaos.data.previewKind
import dev.hearth.casaos.data.uniqueName
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreAndFilesTest {
    @Test fun `store apps read their info, falling back to the main container's entry`() {
        val data = Json.parseToJsonElement(
            """
            {
              "installed": ["Jellyfin"],
              "list": {
                "jellyfin": {
                  "title": {"en_us": "Jellyfin"}, "tagline": {"en_us": "Media server"},
                  "description": {"en_us": "  Stream your media.  "}, "category": "Media", "developer": "Jellyfin",
                  "icon": "https://cdn.example/jellyfin.png",
                  "screenshot_link": ["https://cdn.example/1.png", ""]
                },
                "adguard": {
                  "main": "adguard",
                  "apps": {"adguard": {"title": {"en_us": "AdGuard Home"}, "category": "Network", "author": "AdGuard"}}
                },
                "bare": {}
              }
            }
            """,
        )
        val apps = parseStoreApps(data)
        assertEquals(listOf("AdGuard Home", "bare", "Jellyfin"), apps.map { it.title })
        val jf = apps.first { it.id == "jellyfin" }
        assertTrue(jf.installed) // ids compare case-insensitively
        assertEquals("Stream your media.", jf.description)
        assertEquals(listOf("https://cdn.example/1.png"), jf.screenshots)
        val ad = apps.first { it.id == "adguard" }
        assertFalse(ad.installed)
        assertEquals("Network", ad.category)
        assertEquals("AdGuard", ad.developer)
        assertNull(apps.first { it.id == "bare" }.iconUrl)
    }

    @Test fun `unique names add a number before the extension`() {
        assertEquals("a.jpg", uniqueName("a.jpg", setOf("b.jpg")))
        assertEquals("a (1).jpg", uniqueName("a.jpg", setOf("a.jpg")))
        assertEquals("a (2).jpg", uniqueName("a.jpg", setOf("a.jpg", "a (1).jpg")))
        assertEquals("notes (1)", uniqueName("notes", setOf("notes")))
        assertEquals(".env (1)", uniqueName(".env", setOf(".env")))
    }

    @Test fun `previews cover common media and text`() {
        assertEquals(PreviewKind.Image, previewKind("IMG_001.JPG"))
        assertEquals(PreviewKind.Video, previewKind("clip.mp4"))
        assertEquals(PreviewKind.Audio, previewKind("song.flac"))
        assertEquals(PreviewKind.Text, previewKind("docker-compose.yml"))
        assertEquals(PreviewKind.Text, previewKind(".env"))
        assertNull(previewKind("archive.zip"))
        assertNull(previewKind("report.pdf"))
    }

    @Test fun `photos get a thumbnail when one can be made from their preview or without downloading too much`() {
        fun entry(name: String, size: Long, isDir: Boolean = false) = FileEntry(name, "/DATA/$name", isDir, size, null)
        assertTrue(hasThumbnail(entry("IMG_001.JPG", 4_000_000)))
        assertTrue(hasThumbnail(entry("scan.png", THUMBNAIL_MAX_BYTES)))
        // Too big to download whole, but a JPEG's preview is in its first few KB.
        assertTrue(hasThumbnail(entry("panorama.jpg", THUMBNAIL_MAX_BYTES + 1)))
        assertFalse(hasThumbnail(entry("panorama.png", THUMBNAIL_MAX_BYTES + 1)))
        assertFalse(hasThumbnail(entry("empty.jpg", 0)))
        assertFalse(hasThumbnail(entry("clip.mp4", 4_000_000)))
        assertFalse(hasThumbnail(entry("notes.txt", 100)))
        // A folder can be named like a photo.
        assertFalse(hasThumbnail(entry("holiday.jpg", 4096, isDir = true)))
    }
}
