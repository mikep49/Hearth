package dev.hearth.casaos

import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.DocumentRef
import dev.hearth.casaos.data.SavedUploads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SavedUploadsTest {
    @get:Rule val folder = TemporaryFolder()
    private val dir: File get() = folder.root

    private val note = DocumentRef("s", "/DATA/Docs/note.txt")
    private val photo = DocumentRef("s", "/DATA/Photos/a.jpg")

    /** What the server got, as "path=contents". */
    private val sent = mutableListOf<String>()
    private val uploaded = mutableListOf<DocumentRef>()
    private val onPhone = mutableListOf<String>()
    private val failures = mutableListOf<String>()
    private var tries = 0
    private var wakeUps = 0
    private var time = 0L

    private var serverError: Exception? = null
    private var phoneTakesIt = true

    private fun TestScope.saves(send: (suspend (DocumentRef, File) -> Unit)? = null) = SavedUploads(
        dir,
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        send = send ?: { ref, file ->
            tries++
            serverError?.let { throw it }
            sent += "${ref.path}=${file.readText()}"
        },
        keepOnPhone = { name, file -> phoneTakesIt.also { if (it) onPhone += "$name=${file.readText()}" } },
        onUploaded = { uploaded += it },
        onFailed = { ref, reason, kept -> failures += "${ref.name}: $reason, ${if (kept) "on the phone" else "still waiting"}" },
        keepAlive = { wakeUps++ },
        retryDelayMs = 1,
        now = { time },
    )

    /** An app opening [ref], writing [text] and closing it. */
    private fun SavedUploads.save(ref: DocumentRef, text: String, startsFrom: String? = null, failed: Boolean = false) {
        val file = open(ref, startsFrom)
        if (text != startsFrom) file.writeText(text)
        closed(file, failed)
    }

    /** An app opening [ref] to replace it, or with [startsFrom] to change what's there. */
    private fun SavedUploads.open(ref: DocumentRef, startsFrom: String? = null): File {
        val file = newFile()
        if (startsFrom != null) file.writeText(startsFrom)
        opened(file, ref, replaces = startsFrom == null)
        return file
    }

    private fun leftOnPhone() = dir.listFiles().orEmpty().map { it.name }

    @Test
    fun `a save is uploaded and then removed from the phone`() = runTest {
        val s = saves()
        s.save(note, "hello")
        assertTrue(s.finish())
        assertEquals(listOf("/DATA/Docs/note.txt=hello"), sent)
        assertEquals(listOf(note), uploaded)
        assertEquals(emptyList<String>(), leftOnPhone())
        assertEquals(1, wakeUps)
    }

    @Test
    fun `nothing is uploaded when the app's write failed or changed nothing`() = runTest {
        val s = saves()
        s.save(note, "half of it", failed = true)
        s.save(note, "as it was", startsFrom = "as it was")
        assertTrue(s.finish())
        assertEquals(emptyList<String>(), sent)
        assertEquals(emptyList<String>(), leftOnPhone())
        assertEquals(0, wakeUps)
    }

    @Test
    fun `a file opened to replace is uploaded even when left empty`() = runTest {
        val s = saves()
        s.save(note, "")
        assertTrue(s.finish())
        assertEquals(listOf("/DATA/Docs/note.txt="), sent)
    }

    @Test
    fun `a changed file is uploaded`() = runTest {
        val s = saves()
        s.save(note, "the old text and more", startsFrom = "the old text")
        assertTrue(s.finish())
        assertEquals(listOf("/DATA/Docs/note.txt=the old text and more"), sent)
    }

    @Test
    fun `a save Android cut short is uploaded by the next run`() = runTest {
        // The first run of Hearth is ended while it's still uploading.
        saves(send = { _, _ -> awaitCancellation() }).save(note, "hello")
        assertEquals(emptyList<String>(), sent)

        val next = saves()
        assertTrue(next.unfinished())
        next.resume()
        assertTrue(next.finish())
        assertEquals(listOf("/DATA/Docs/note.txt=hello"), sent)
        assertEquals(emptyList<String>(), leftOnPhone())
        assertEquals(2, wakeUps) // one for the save, one for carrying on
    }

    @Test
    fun `with nothing left over the next run does nothing`() = runTest {
        val s = saves()
        s.resume()
        assertFalse(s.unfinished())
        assertEquals(0, wakeUps)
    }

    @Test
    fun `a save the server won't take goes to the phone after three tries`() = runTest {
        serverError = CasaException.Network("no route to host")
        val s = saves()
        s.save(note, "hello")
        assertTrue(s.finish())
        assertEquals(3, tries)
        assertEquals(listOf("note.txt=hello"), onPhone)
        assertEquals(listOf("note.txt: no route to host, on the phone"), failures)
        assertEquals(emptyList<DocumentRef>(), uploaded)
        assertEquals(emptyList<String>(), leftOnPhone())
    }

    @Test
    fun `signed out isn't tried again`() = runTest {
        serverError = CasaException.Unauthorized()
        val s = saves()
        s.save(note, "hello")
        assertTrue(s.finish())
        assertEquals(1, tries)
        assertEquals(listOf("note.txt=hello"), onPhone)
    }

    @Test
    fun `a save that can go nowhere is kept, said once, and tried again`() = runTest {
        serverError = CasaException.Server(507, "disk full")
        phoneTakesIt = false
        val s = saves()
        s.save(note, "hello")
        assertFalse(s.finish())
        assertFalse(s.finish())
        assertEquals(listOf("note.txt: disk full, still waiting"), failures)
        assertEquals(2, leftOnPhone().size)

        // Other saves aren't held up by it.
        serverError = null
        s.save(photo, "jpeg")
        assertEquals("/DATA/Photos/a.jpg=jpeg", sent.last())

        assertTrue(s.finish())
        assertEquals(setOf("/DATA/Photos/a.jpg=jpeg", "/DATA/Docs/note.txt=hello"), sent.toSet())
        assertEquals(emptyList<String>(), leftOnPhone())
    }

    @Test
    fun `a newer save of the same file replaces one still waiting`() = runTest {
        serverError = CasaException.Server(507, "disk full")
        phoneTakesIt = false
        val s = saves()
        s.save(note, "first")
        assertFalse(s.finish())

        serverError = null
        s.save(note, "second")
        assertTrue(s.finish())
        assertEquals(listOf("/DATA/Docs/note.txt=second"), sent)
        assertEquals(emptyList<String>(), leftOnPhone())
    }

    @Test
    fun `saves go up in the order they were written`() = runTest {
        val first = saves(send = { _, _ -> awaitCancellation() })
        first.save(note, "one")
        dir.listFiles()!!.first { it.name.endsWith(".save") }.setLastModified(1_000)
        first.save(photo, "two")
        dir.listFiles()!!.first { it.name.endsWith(".save") && it.lastModified() != 1_000L }.setLastModified(2_000)

        val next = saves()
        assertTrue(next.finish())
        assertEquals(listOf("/DATA/Docs/note.txt=one", "/DATA/Photos/a.jpg=two"), sent)
    }

    @Test
    fun `a file an earlier run left open is uploaded once it's been left alone`() = runTest {
        // Hearth is ended while the app is still writing, so it never hears the file closed.
        val file = saves().open(note)
        file.writeText("written after Hearth was gone")

        val next = saves()
        time = file.lastModified() + 30_000
        assertFalse(next.finish()) // the app may still be writing
        assertEquals(emptyList<String>(), sent)

        time = file.lastModified() + 5 * 60_000
        assertTrue(next.finish())
        assertEquals(listOf("/DATA/Docs/note.txt=written after Hearth was gone"), sent)
        assertEquals(emptyList<String>(), leftOnPhone())
    }

    @Test
    fun `a file an earlier run left open and unwritten is dropped`() = runTest {
        val replacing = saves().open(note)
        val changing = saves().open(photo, startsFrom = "jpeg")
        time = maxOf(replacing.lastModified(), changing.lastModified()) + 5 * 60_000

        val next = saves()
        assertTrue(next.finish())
        // Not even the one opened to replace: uploading it would empty a file the app may never have written.
        assertEquals(emptyList<String>(), sent)
        assertEquals(emptyList<String>(), leftOnPhone())
    }

    @Test
    fun `a new save replaces what an earlier run left open for the same file`() = runTest {
        val old = saves().open(note)
        old.writeText("older")

        val next = saves()
        next.save(note, "newer")
        time = old.lastModified() + 5 * 60_000
        assertTrue(next.finish())
        assertEquals(listOf("/DATA/Docs/note.txt=newer"), sent)
    }

    @Test
    fun `files with no note of where they go are cleared, but not ones being written`() = runTest {
        File(dir, "stray.part").writeText("x")
        File(dir, "stray2.json").writeText("not json")
        val s = saves()
        val writing = s.open(note)
        assertFalse(s.unfinished())
        assertEquals(setOf(writing.name, writing.nameWithoutExtension + ".json"), leftOnPhone().toSet())
        writing.writeText("done")
        s.closed(writing, failed = false)
        assertTrue(s.finish())
        assertEquals(listOf("/DATA/Docs/note.txt=done"), sent)
    }

    @Test
    fun `one wake-up covers saves made before it starts`() = runTest {
        val s = saves()
        s.save(note, "a")
        s.save(photo, "b")
        assertEquals(1, wakeUps)
        assertTrue(s.finish())
        // The wake-up has run, so a later save asks for another.
        s.save(note, "c")
        assertEquals(2, wakeUps)
    }
}
