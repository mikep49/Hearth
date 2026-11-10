package dev.hearth.casaos

import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.FileIndex
import dev.hearth.casaos.data.FolderListing
import dev.hearth.casaos.data.renamed
import dev.hearth.casaos.data.searchFiles
import dev.hearth.casaos.data.walkFolders
import dev.hearth.casaos.data.without
import dev.hearth.casaos.data.withoutNested
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSearchTest {
    private fun dir(path: String) = FileEntry(path.substringAfterLast('/'), path, isDir = true, size = 0, modified = null)
    private fun file(path: String) = FileEntry(path.substringAfterLast('/'), path, isDir = false, size = 1, modified = null)

    private val tree = mapOf(
        "/DATA" to listOf(dir("/DATA/Media"), dir("/DATA/Locked"), file("/DATA/notes.txt")),
        "/DATA/Media" to listOf(dir("/DATA/Media/Movies"), file("/DATA/Media/cover.jpg")),
        "/DATA/Media/Movies" to listOf(file("/DATA/Media/Movies/Notes on Film.mkv")),
    )

    private suspend fun list(path: String): FolderListing =
        tree[path]?.let { FolderListing(path, it) } ?: throw CasaException.Server(403, "Permission denied")

    @Test fun `the walk finds everything below the root and skips folders it can't read`() = runTest {
        val index = walkFolders("/DATA", ::list)
        assertTrue(index.done)
        assertFalse(index.truncated)
        assertEquals(4, index.foldersScanned)
        assertEquals(
            setOf("/DATA/Media", "/DATA/Locked", "/DATA/notes.txt", "/DATA/Media/Movies", "/DATA/Media/cover.jpg", "/DATA/Media/Movies/Notes on Film.mkv"),
            index.entries.map { it.path }.toSet(),
        )
    }

    @Test fun `a lost session ends the walk`() = runTest {
        val result = runCatching { walkFolders("/DATA", { throw CasaException.Unauthorized() }) }
        assertTrue(result.exceptionOrNull() is CasaException.Unauthorized)
    }

    @Test fun `search ignores case, ranks by how well the name matches, and can skip the open folder`() = runTest {
        val index = walkFolders("/DATA", ::list)
        assertEquals(listOf("/DATA/notes.txt", "/DATA/Media/Movies/Notes on Film.mkv"), searchFiles(index.entries, "NOTES").entries.map { it.path })
        assertEquals(listOf("/DATA/Media/Movies/Notes on Film.mkv"), searchFiles(index.entries, "notes", skipDir = "/DATA").entries.map { it.path })
        assertEquals(0, searchFiles(index.entries, "  ").total)
        val limited = searchFiles(index.entries, "e", limit = 2)
        assertEquals(2, limited.entries.size)
        assertEquals(6, limited.total)
    }

    @Test fun `deletes and renames carry into the index, including folder contents`() = runTest {
        val index = walkFolders("/DATA", ::list)
        val renamed = index.renamed("/DATA/Media", "/DATA/Videos")
        assertTrue(renamed.entries.any { it.path == "/DATA/Videos/Movies/Notes on Film.mkv" })
        assertTrue(renamed.entries.any { it.path == "/DATA/Videos" && it.name == "Videos" })
        assertFalse(renamed.entries.any { it.path.startsWith("/DATA/Media") })

        val deleted = renamed.without(listOf("/DATA/Videos"))
        assertEquals(setOf("/DATA/Locked", "/DATA/notes.txt"), deleted.entries.map { it.path }.toSet())
    }

    @Test fun `a sibling with a similar name survives a delete`() {
        val index = FileIndex("/DATA", listOf(dir("/DATA/Media"), dir("/DATA/Media2"), file("/DATA/Media/a")), 1, done = true)
        assertEquals(listOf("/DATA/Media2"), index.without(listOf("/DATA/Media")).entries.map { it.path })
    }

    @Test fun `a selection drops items inside a folder that is selected too`() {
        val picked = listOf(dir("/DATA/Media"), file("/DATA/Media/cover.jpg"), dir("/DATA/Media/Movies"), file("/DATA/Media.txt"), file("/DATA/notes.txt"))
        assertEquals(listOf("/DATA/Media", "/DATA/Media.txt", "/DATA/notes.txt"), picked.withoutNested().map { it.path })
    }
}
