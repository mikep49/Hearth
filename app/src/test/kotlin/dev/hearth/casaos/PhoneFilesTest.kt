package dev.hearth.casaos

import dev.hearth.casaos.data.createSharedFile
import dev.hearth.casaos.data.pruneSharedFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhoneFilesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val day = 24 * 60 * 60 * 1000L

    @Test fun `opening another file with the same name preserves the first copy`() {
        val directory = temporaryFolder.newFolder("open")
        val now = System.currentTimeMillis()
        val first = createSharedFile(directory, "report.pdf", now).apply { writeText("first") }
        val second = createSharedFile(directory, "report.pdf", now + day / 2).apply { writeText("second") }

        assertNotEquals(first, second)
        assertEquals("report.pdf", first.name)
        assertEquals("report.pdf", second.name)
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
    }

    @Test fun `cleanup expires old copies but retains copies for a full day`() {
        val directory = temporaryFolder.newFolder("open")
        val now = System.currentTimeMillis()
        val oldCopy = createSharedFile(directory, "old.txt", now).apply {
            writeText("old")
            assertTrue(setLastModified(now - day - 1))
        }
        assertTrue(oldCopy.parentFile!!.setLastModified(now - day - 1))
        // Earlier versions put copies directly in open/.
        val legacyCopy = File(directory, "legacy.txt").apply {
            writeText("legacy")
            assertTrue(setLastModified(now - day - 1))
        }
        val boundaryCopy = File(directory, "boundary.txt").apply {
            writeText("keep")
            assertTrue(setLastModified(now - day))
        }

        createSharedFile(directory, "next.txt", now)

        assertFalse(oldCopy.exists())
        assertFalse(oldCopy.parentFile!!.exists())
        assertFalse(legacyCopy.exists())
        assertEquals("keep", boundaryCopy.readText())
    }

    @Test fun `pruning on its own removes expired copies and keeps recent ones`() {
        val directory = temporaryFolder.newFolder("open")
        val now = System.currentTimeMillis()
        val oldCopy = createSharedFile(directory, "old.mp4", now).apply {
            writeText("old")
            assertTrue(setLastModified(now - day - 1))
        }
        assertTrue(oldCopy.parentFile!!.setLastModified(now - day - 1))
        val recentCopy = createSharedFile(directory, "recent.mp4", now).apply { writeText("recent") }

        pruneSharedFiles(directory, now)

        assertFalse(oldCopy.parentFile!!.exists())
        assertEquals("recent", recentCopy.readText())
        assertTrue(directory.exists())
    }

    @Test fun `an old logs folder does not cause recent shared logs to be deleted`() {
        val directory = temporaryFolder.newFolder("open")
        val logs = File(directory, "logs").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val recentLog = File(logs, "app-logs.txt").apply { writeText("recent logs") }
        val expiredLog = File(logs, "old-logs.txt").apply {
            writeText("old logs")
            assertTrue(setLastModified(now - day - 1))
        }
        assertTrue(logs.setLastModified(now - day - 1))

        createSharedFile(directory, "next.txt", now)

        assertEquals("recent logs", recentLog.readText())
        assertFalse(expiredLog.exists())
    }
}
