package dev.hearth.casaos

import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.FileSort
import dev.hearth.casaos.data.SortBy
import dev.hearth.casaos.data.arranged
import dev.hearth.casaos.data.isHiddenPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSortTest {
    private fun dir(name: String, modified: String? = null) = FileEntry(name, "/DATA/$name", isDir = true, size = 4096, modified = modified)
    private fun file(name: String, size: Long, modified: String?) = FileEntry(name, "/DATA/$name", isDir = false, size = size, modified = modified)

    private val entries = listOf(
        file("b.txt", 300, "2026-03-01T10:00:00Z"),
        dir("Media", "2026-01-01T00:00:00Z"),
        file("A.jpg", 100, "2026-05-01T10:00:00Z"),
        file(".env", 50, "2026-06-01T10:00:00Z"),
        dir(".config", "2026-07-01T00:00:00Z"),
        file("c.mkv", 900, null),
        dir("apps", "2026-02-01T00:00:00Z"),
    )

    private fun names(sort: FileSort) = entries.arranged(sort).map { it.name }

    @Test fun `name order puts folders first and ignores case`() {
        assertEquals(listOf("apps", "Media", "A.jpg", "b.txt", "c.mkv"), names(FileSort()))
        assertEquals(listOf("Media", "apps", "c.mkv", "b.txt", "A.jpg"), names(FileSort(descending = true)))
    }

    @Test fun `size order keeps folders by name`() {
        assertEquals(listOf("apps", "Media", "c.mkv", "b.txt", "A.jpg"), names(FileSort(SortBy.Size, descending = true)))
        assertEquals(listOf("apps", "Media", "A.jpg", "b.txt", "c.mkv"), names(FileSort(SortBy.Size)))
    }

    @Test fun `date order puts files without a date last either way`() {
        assertEquals(listOf("apps", "Media", "A.jpg", "b.txt", "c.mkv"), names(FileSort(SortBy.Date, descending = true)))
        assertEquals(listOf("Media", "apps", "b.txt", "A.jpg", "c.mkv"), names(FileSort(SortBy.Date)))
    }

    @Test fun `hidden files show only when asked`() {
        assertEquals(listOf(".config", "apps", "Media", ".env", "A.jpg", "b.txt", "c.mkv"), names(FileSort(showHidden = true)))
    }

    @Test fun `picking an order starts it the useful way and picking it again reverses it`() {
        val bySize = FileSort().pick(SortBy.Size)
        assertEquals(FileSort(SortBy.Size, descending = true), bySize)
        assertEquals(FileSort(SortBy.Size, descending = false), bySize.pick(SortBy.Size))
        assertEquals(FileSort(SortBy.Date, descending = true), bySize.pick(SortBy.Date))
        assertEquals(FileSort(SortBy.Name, descending = false), bySize.pick(SortBy.Name))
    }

    @Test fun `a path is hidden when it or a folder above it starts with a dot`() {
        assertTrue(isHiddenPath("/DATA/.config/app.conf", "/DATA"))
        assertTrue(isHiddenPath("/DATA/Media/.thumbs", "/DATA"))
        assertFalse(isHiddenPath("/DATA/Media/photo.jpg", "/DATA"))
    }
}
