package dev.hearth.casaos

import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.FileIndex
import dev.hearth.casaos.data.FolderListing
import dev.hearth.casaos.data.GALLERY_FRESH_MS
import dev.hearth.casaos.data.GalleryLoader
import dev.hearth.casaos.data.IndexSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryLoaderTest {
    private fun dir(path: String) = FileEntry(path.substringAfterLast('/'), path, isDir = true, size = 4096, modified = null)
    private fun photo(path: String, modified: String = "2026-03-04T10:00:00Z") =
        FileEntry(path.substringAfterLast('/'), path, isDir = false, size = 2_000_000, modified = modified)

    /** The server's folders, which the tests change as they go. */
    private val tree = mutableMapOf(
        "/DATA" to listOf(dir("/DATA/Gallery"), dir("/DATA/AppData"), dir("/DATA/Empty")),
        "/DATA/Gallery" to listOf(photo("/DATA/Gallery/a.jpg"), dir("/DATA/Gallery/Trips")),
        "/DATA/Gallery/Trips" to listOf(photo("/DATA/Gallery/Trips/b.jpg", "2026-02-01T10:00:00Z")),
        "/DATA/AppData" to listOf(photo("/DATA/AppData/poster.jpg")),
        "/DATA/Empty" to emptyList(),
    )

    /** Every folder listed, in order. */
    private val listed = mutableListOf<String>()

    /** While set, listing a folder waits for it. */
    private var gate: CompletableDeferred<Unit>? = null
    private var offline = false
    private var now = 1_000_000L
    private var index: IndexSnapshot? = null
    private val errors = mutableListOf<String>()

    private suspend fun list(path: String): FolderListing {
        listed += path
        gate?.await()
        if (offline) throw CasaException.Network("No connection")
        return tree[path]?.let { FolderListing(path, it) } ?: throw CasaException.Server(404, "Not found")
    }

    private fun TestScope.loader(): GalleryLoader {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return GalleryLoader(
            CoroutineScope(backgroundScope.coroutineContext + dispatcher), ::list, "/DATA",
            index = { index }, clock = { now }, onError = { errors += it }, work = dispatcher,
        )
    }

    private fun GalleryLoader.paths() = state.value?.photos?.map { it.path }

    private fun GalleryLoader.scanning() = state.value?.scanning

    /** Opens the gallery at [root], lets it finish looking, and closes it again. */
    private fun GalleryLoader.visit(root: String = "/DATA") {
        open(root, showHidden = false)
        assertEquals(false, scanning())
        close()
    }

    @Test fun `opening again soon shows what was found without walking the folders again`() = runTest {
        val g = loader()
        g.open("/DATA", showHidden = false)
        assertEquals(listOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg"), g.paths())
        assertEquals(false, g.scanning())
        g.close()
        assertNull(g.state.value)
        val walked = listed.size

        now += GALLERY_FRESH_MS - 1
        g.open("/DATA", showHidden = false)
        assertEquals(listOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg"), g.paths())
        assertEquals(false, g.scanning())
        assertEquals(walked, listed.size)
    }

    @Test fun `older photos show straight away while the folders are looked through again`() = runTest {
        val g = loader()
        g.visit()
        tree["/DATA/Gallery"] = tree.getValue("/DATA/Gallery") + photo("/DATA/Gallery/new.jpg", "2026-04-01T10:00:00Z")
        now += GALLERY_FRESH_MS
        gate = CompletableDeferred()
        g.open("/DATA", showHidden = false)
        assertEquals(listOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg"), g.paths())
        assertEquals(true, g.scanning())

        gate!!.complete(Unit)
        assertEquals(listOf("/DATA/Gallery/new.jpg", "/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg"), g.paths())
        assertEquals(false, g.scanning())
        assertTrue(errors.isEmpty())
    }

    @Test fun `pulling to refresh while it's looking already doesn't start over`() = runTest {
        val g = loader()
        g.visit()
        now += GALLERY_FRESH_MS
        gate = CompletableDeferred()
        g.open("/DATA", showHidden = false)
        val walked = listed.size
        g.refresh()
        assertEquals(walked, listed.size)
        gate!!.complete(Unit)
        assertEquals(false, g.scanning())

        // Once it's done, a pull looks again, however recent the photos are.
        val before = listed.size
        g.refresh()
        assertTrue(listed.size > before)
        assertEquals(false, g.scanning())
    }

    @Test fun `a recent search index stands in for the walk`() = runTest {
        index = IndexSnapshot(
            FileIndex(
                "/DATA",
                tree.values.flatten() + listOf(dir("/DATA/Gallery/.cache"), photo("/DATA/Gallery/.cache/x.jpg"), photo("/DATA/Gallery/Trips/c.jpg")),
                foldersScanned = 6, done = true,
            ),
            at = now - 60_000, upToDate = true,
        )
        val g = loader()
        g.open("/DATA", showHidden = false)
        assertEquals(setOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg", "/DATA/Gallery/Trips/c.jpg"), g.paths()?.toSet())
        assertEquals(false, g.scanning())
        assertTrue(listed.isEmpty())

        // A folder inside it, too.
        g.open("/DATA/Gallery/Trips", showHidden = false)
        assertEquals(setOf("/DATA/Gallery/Trips/b.jpg", "/DATA/Gallery/Trips/c.jpg"), g.paths()?.toSet())
        assertTrue(listed.isEmpty())
    }

    @Test fun `a search index that's out of date shows while the folders are looked through`() = runTest {
        index = IndexSnapshot(FileIndex("/DATA", tree.values.flatten(), foldersScanned = 5, done = true), at = now, upToDate = false)
        val g = loader()
        gate = CompletableDeferred()
        g.open("/DATA", showHidden = false)
        assertEquals(listOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg"), g.paths())
        assertEquals(true, g.scanning())
        assertEquals(listOf("/DATA"), listed)
        gate!!.complete(Unit)
        assertEquals(false, g.scanning())
    }

    @Test fun `an old search index isn't shown over photos found since, but a newer, recent one is`() = runTest {
        val g = loader()
        g.visit()
        now += GALLERY_FRESH_MS
        // Older than what the gallery found.
        index = IndexSnapshot(FileIndex("/DATA", listOf(photo("/DATA/Gallery/old.jpg")), 1, done = true), at = 0, upToDate = true)
        gate = CompletableDeferred()
        g.open("/DATA", showHidden = false)
        assertEquals(listOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg"), g.paths())
        gate!!.complete(Unit)
        g.close()
        gate = null

        now += GALLERY_FRESH_MS
        index = IndexSnapshot(FileIndex("/DATA", listOf(photo("/DATA/Gallery/fresh.jpg")), 1, done = true), at = now, upToDate = true)
        val walked = listed.size
        g.open("/DATA", showHidden = false)
        assertEquals(listOf("/DATA/Gallery/fresh.jpg"), g.paths())
        assertEquals(walked, listed.size)
    }

    @Test fun `an index that was cut short or is still being built is no use`() = runTest {
        val whole = FileIndex("/DATA", tree.values.flatten(), foldersScanned = 5, done = true)
        for (partial in listOf(whole.copy(truncated = true), whole.copy(done = false))) {
            index = IndexSnapshot(partial, at = now, upToDate = true)
            listed.clear()
            val g = loader()
            gate = CompletableDeferred()
            g.open("/DATA", showHidden = false)
            assertEquals(emptyList<String>(), g.paths())
            assertEquals(listOf("/DATA"), listed)
            gate!!.complete(Unit)
            assertEquals(listOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg"), g.paths())
        }
    }

    @Test fun `after changes it can't follow, it looks again next time`() = runTest {
        val g = loader()
        g.visit()
        g.outdated()
        gate = CompletableDeferred()
        g.open("/DATA", showHidden = false)
        assertEquals(listOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg"), g.paths())
        assertEquals(true, g.scanning())
        gate!!.complete(Unit)
        assertEquals(false, g.scanning())
    }

    @Test fun `a change made while it looks makes it look again next time`() = runTest {
        val g = loader()
        gate = CompletableDeferred()
        g.open("/DATA", showHidden = false)
        g.outdated()
        gate!!.complete(Unit)
        g.close()
        gate = null
        val walked = listed.size
        g.open("/DATA", showHidden = false)
        assertTrue(listed.size > walked)
    }

    @Test fun `a photo deleted while it looks doesn't come back when the look is done`() = runTest {
        val g = loader()
        g.visit()
        now += GALLERY_FRESH_MS
        gate = CompletableDeferred()
        g.open("/DATA", showHidden = false)
        g.deleted(setOf("/DATA/Gallery/a.jpg"))
        assertEquals(listOf("/DATA/Gallery/Trips/b.jpg"), g.paths())
        // The walk listed the folder before the photo went.
        gate!!.complete(Unit)
        assertEquals(listOf("/DATA/Gallery/Trips/b.jpg"), g.paths())
    }

    @Test fun `a deleted folder's photos are gone the next time, without looking again`() = runTest {
        val g = loader()
        g.visit()
        g.visit("/DATA/Gallery/Trips")
        g.deleted(setOf("/DATA/Gallery/Trips"))
        val walked = listed.size
        g.open("/DATA", showHidden = false)
        assertEquals(listOf("/DATA/Gallery/a.jpg"), g.paths())
        assertEquals(walked, listed.size)
        // The deleted folder's own gallery is forgotten.
        tree.remove("/DATA/Gallery/Trips")
        g.open("/DATA/Gallery/Trips", showHidden = false)
        assertTrue(listed.size > walked)
    }

    @Test fun `a renamed folder's photos show under its new name, and it looks again`() = runTest {
        val g = loader()
        g.visit()
        g.renamed("/DATA/Gallery/Trips", "/DATA/Gallery/Holidays")
        gate = CompletableDeferred()
        g.open("/DATA", showHidden = false)
        assertEquals(listOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Holidays/b.jpg"), g.paths())
        assertEquals(true, g.scanning())
        gate!!.complete(Unit)
    }

    @Test fun `a rename somewhere else leaves the photos alone`() = runTest {
        val g = loader()
        g.visit("/DATA/Gallery")
        g.renamed("/DATA/Empty", "/DATA/Nothing")
        val walked = listed.size
        g.open("/DATA/Gallery", showHidden = false)
        assertEquals(false, g.scanning())
        assertEquals(walked, listed.size)
    }

    @Test fun `it remembers the last few folders it was opened in`() = runTest {
        val g = loader()
        for (root in listOf("/DATA", "/DATA/Gallery", "/DATA/Gallery/Trips", "/DATA/AppData")) g.visit(root)
        // Opening the first again makes it the most recent.
        g.visit("/DATA")
        g.visit("/DATA/Empty")

        var walked = listed.size
        g.visit("/DATA")
        assertEquals(walked, listed.size)
        g.visit("/DATA/Gallery")
        assertTrue("the folder opened longest ago is forgotten", listed.size > walked)

        walked = listed.size
        g.open("/DATA/Gallery", showHidden = true)
        assertTrue("hidden files shown is remembered apart", listed.size > walked)
    }

    @Test fun `a failed refresh keeps the photos and says so`() = runTest {
        val g = loader()
        g.visit()
        now += GALLERY_FRESH_MS
        offline = true
        g.open("/DATA", showHidden = false)
        assertEquals(listOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg"), g.paths())
        assertEquals(false, g.scanning())
        assertEquals(listOf("Couldn't refresh the gallery: No connection"), errors)
    }

    @Test fun `with nothing to show, a failed look shows why`() = runTest {
        offline = true
        val g = loader()
        g.open("/DATA", showHidden = false)
        assertEquals("No connection", g.state.value?.error)
        assertTrue(errors.isEmpty())
        offline = false
        g.refresh()
        assertNull(g.state.value?.error)
        assertEquals(listOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg"), g.paths())
    }

    @Test fun `closing stops the look, and what it was looking for isn't kept`() = runTest {
        val g = loader()
        gate = CompletableDeferred()
        g.open("/DATA", showHidden = false)
        g.close()
        gate!!.complete(Unit)
        assertNull(g.state.value)
        assertFalse(listed.isEmpty())

        listed.clear()
        g.open("/DATA", showHidden = false)
        assertEquals(listOf("/DATA/Gallery/a.jpg", "/DATA/Gallery/Trips/b.jpg"), g.paths())
        assertFalse(listed.isEmpty())
    }
}
