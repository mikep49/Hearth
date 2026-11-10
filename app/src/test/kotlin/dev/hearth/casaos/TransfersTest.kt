package dev.hearth.casaos

import dev.hearth.casaos.data.Transfer
import dev.hearth.casaos.data.Transfers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TransfersTest {
    private val messages = mutableListOf<String>()
    private val outcomes = mutableListOf<String>()
    private var keptAlive = 0

    private fun kotlinx.coroutines.test.TestScope.transfers() =
        Transfers(backgroundScope + UnconfinedTestDispatcher(testScheduler), keepAlive = { keptAlive++ }, onOutcome = { outcomes += it })

    private operator fun kotlinx.coroutines.CoroutineScope.plus(context: kotlin.coroutines.CoroutineContext) =
        kotlinx.coroutines.CoroutineScope(coroutineContext + context)

    @Test
    fun `reports progress, then the result, and clears`() = runTest {
        val t = transfers()
        val gate = CompletableDeferred<Unit>()
        assertTrue(t.start("Downloading a", "Download failed", onMessage = { messages += it }) { p ->
            p.update(1, 4)
            gate.await()
            "Saved a"
        })
        assertEquals(Transfer("Downloading a", 0.25f), t.current.value)
        assertEquals(1, keptAlive)
        gate.complete(Unit)
        assertNull(t.current.value)
        assertEquals(listOf("Saved a"), messages)
        assertEquals(listOf("Saved a"), outcomes)
    }

    @Test
    fun `only one runs at a time`() = runTest {
        val t = transfers()
        t.start("first", "failed", onMessage = {}) { awaitCancellation() }
        assertFalse(t.start("second", "failed", onMessage = {}) { "done" })
        assertEquals("first", t.current.value?.label)
        assertEquals(1, keptAlive)
    }

    @Test
    fun `a new label starts progress over`() = runTest {
        val t = transfers()
        t.start("one", "failed", onMessage = {}) { p ->
            p.update(5, 10)
            p.label("two")
            awaitCancellation()
        }
        assertEquals(Transfer("two"), t.current.value)
    }

    @Test
    fun `cancelling by hand says so on screen only`() = runTest {
        val t = transfers()
        t.start("Uploading", "Upload failed", cancelled = "Upload cancelled", onMessage = { messages += it }) { awaitCancellation() }
        t.cancel()
        assertNull(t.current.value)
        assertEquals(listOf("Upload cancelled"), messages)
        assertTrue(outcomes.isEmpty())
        // And the next one can start.
        assertTrue(t.start("next", "failed", onMessage = {}) { null })
    }

    @Test
    fun `a stop that wasn't the user's is reported with its reason`() = runTest {
        val t = transfers()
        t.start("Uploading", "Upload failed", onMessage = { messages += it }) { awaitCancellation() }
        t.cancel("Stopped by Android")
        assertEquals(listOf("Stopped by Android"), messages)
        assertEquals(listOf("Stopped by Android"), outcomes)
    }

    @Test
    fun `a failure becomes a message`() = runTest {
        val t = transfers()
        t.start("Downloading", "Download failed", onMessage = { messages += it }) { throw IOException("disk full") }
        assertNull(t.current.value)
        assertEquals(listOf("Download failed: disk full"), messages)
        assertEquals(messages, outcomes)
    }

    @Test
    fun `a transfer still starts when it can't be kept alive`() = runTest {
        val t = Transfers(backgroundScope + UnconfinedTestDispatcher(testScheduler), keepAlive = { error("not allowed") })
        assertTrue(t.start("Downloading", "failed", onMessage = { messages += it }) { "ok" })
        assertEquals(listOf("ok"), messages)
    }
}
