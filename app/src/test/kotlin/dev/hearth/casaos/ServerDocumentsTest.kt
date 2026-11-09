package dev.hearth.casaos

import dev.hearth.casaos.data.DocumentRef
import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.RangeReader
import dev.hearth.casaos.data.ServerProfile
import dev.hearth.casaos.data.cleanFileName
import dev.hearth.casaos.data.modifiedMillis
import dev.hearth.casaos.data.rootSummaries
import dev.hearth.casaos.data.skipFully
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ServerDocumentsTest {
    @Test fun `document ids round-trip`() {
        val ref = DocumentRef("abc-123", "/DATA/Media/Photos/a:b.jpg")
        assertEquals("abc-123:/DATA/Media/Photos/a:b.jpg", ref.id)
        assertEquals(ref, DocumentRef.parse(ref.id))
        assertEquals(DocumentRef.root("abc-123"), DocumentRef.parse("abc-123:/DATA"))
        assertTrue(DocumentRef.root("s").isRoot)
    }

    @Test fun `ids can't reach outside DATA or climb out of a folder`() {
        assertNull(DocumentRef.parse("s:/etc/passwd"))
        assertNull(DocumentRef.parse("s:/DATAX/file"))
        assertNull(DocumentRef.parse("s:/DATA/../etc"))
        assertNull(DocumentRef.parse("s:/DATA/Photos/../../etc"))
        assertNull(DocumentRef.parse("s:/DATA/./x"))
        assertNull(DocumentRef.parse("s:/DATA//x"))
        assertNull(DocumentRef.parse("s:/DATA/x/"))
        assertNull(DocumentRef.parse(":/DATA"))
        assertNull(DocumentRef.parse("/DATA"))
        // Dots inside a name are fine.
        assertEquals("..hidden", DocumentRef.parse("s:/DATA/..hidden")?.name)
    }

    @Test fun `a folder contains only what's below it, on the same server`() {
        val photos = DocumentRef("s", "/DATA/Photos")
        assertTrue(photos.contains(DocumentRef("s", "/DATA/Photos/2024/a.jpg")))
        assertFalse(photos.contains(photos))
        assertFalse(photos.contains(DocumentRef("s", "/DATA/Photos2/a.jpg")))
        assertFalse(photos.contains(DocumentRef("other", "/DATA/Photos/a.jpg")))
        assertTrue(DocumentRef.root("s").contains(photos))
    }

    @Test fun `parent and child`() {
        val ref = DocumentRef("s", "/DATA/Photos/a.jpg")
        assertEquals(DocumentRef("s", "/DATA/Photos"), ref.parent())
        assertEquals(ref, ref.parent().child("a.jpg"))
        assertEquals("a.jpg", ref.name)
    }

    @Test fun `names lose slashes and can't be dots or blank`() {
        assertEquals("a_b.txt", cleanFileName("a/b.txt"))
        assertEquals("notes", cleanFileName("  notes "))
        assertNull(cleanFileName(".."))
        assertNull(cleanFileName("."))
        assertNull(cleanFileName("   "))
        assertEquals(".env", cleanFileName(".env"))
    }

    @Test fun `servers sharing an address are told apart by username`() {
        val servers = listOf(
            ServerProfile("1", "http://casaos.local", "alex", signedIn = true),
            ServerProfile("2", "http://casaos.local", "sam", signedIn = true),
            ServerProfile("3", "http://192.168.1.20:8080", "alex", signedIn = true),
        )
        assertEquals(
            mapOf("1" to "alex@casaos.local", "2" to "sam@casaos.local", "3" to "192.168.1.20:8080"),
            rootSummaries(servers),
        )
    }

    @Test fun `modified times read with or without an offset`() {
        fun entry(modified: String?) = FileEntry("a", "/DATA/a", false, 1, modified)
        assertEquals(1_700_000_000_000L, entry("2023-11-14T22:13:20Z").modifiedMillis())
        assertEquals(1_700_000_000_000L, entry("2023-11-14T23:13:20+01:00").modifiedMillis())
        assertNull(entry("yesterday").modifiedMillis())
        assertNull(entry(null).modifiedMillis())
    }

    @Test fun `skipping reads past streams that won't skip`() {
        val stubborn = object : InputStream() {
            val inner = ByteArrayInputStream(ByteArray(100) { it.toByte() })
            override fun read() = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
            override fun skip(n: Long) = 0L
        }
        assertEquals(40L, skipFully(stubborn, 40))
        assertEquals(40, stubborn.read())
        assertEquals(59L, skipFully(stubborn, 1000)) // only 59 left
    }

    /** A pretend server file that records where each download started. */
    private class FakeFile(size: Int) {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val opens = mutableListOf<Long>()
        var failNextRead = false
        fun open(offset: Long): InputStream {
            opens += offset
            val inner = ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
            return object : InputStream() {
                override fun read() = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (failNextRead) { failNextRead = false; throw IOException("connection reset") }
                    return inner.read(b, off, minOf(len, 1000)) // short reads, like a network stream
                }
            }
        }
    }

    @Test fun `reading on keeps one download, jumping back starts a new one`() {
        val file = FakeFile(3_000_000)
        val reader = RangeReader(file::open)
        val buf = ByteArray(128 * 1024)

        assertEquals(buf.size, reader.read(0, buf.size, buf))
        assertArrayEquals(file.bytes.copyOfRange(0, buf.size), buf)
        assertEquals(buf.size, reader.read(buf.size.toLong(), buf.size, buf))
        assertArrayEquals(file.bytes.copyOfRange(buf.size, 2 * buf.size), buf)
        assertEquals(listOf(0L), file.opens)

        reader.read(10, 100, buf) // back to the start
        assertEquals(listOf(0L, 10L), file.opens)
        assertArrayEquals(file.bytes.copyOfRange(10, 110), buf.copyOf(100))
    }

    @Test fun `a short jump forward skips, a long one starts over`() {
        val file = FakeFile(3_000_000)
        val reader = RangeReader(file::open)
        val buf = ByteArray(1000)
        reader.read(0, 1000, buf)
        reader.read(200_000, 1000, buf)
        assertEquals(listOf(0L), file.opens)
        assertArrayEquals(file.bytes.copyOfRange(200_000, 201_000), buf)
        reader.read(2_500_000, 1000, buf)
        assertEquals(listOf(0L, 2_500_000L), file.opens)
        assertArrayEquals(file.bytes.copyOfRange(2_500_000, 2_501_000), buf)
    }

    @Test fun `the end of the file gives a short read, then nothing`() {
        val file = FakeFile(5000)
        val reader = RangeReader(file::open)
        val buf = ByteArray(4096)
        assertEquals(4096, reader.read(0, 4096, buf))
        assertEquals(904, reader.read(4096, 4096, buf))
        assertEquals(0, reader.read(5000, 4096, buf))
    }

    @Test fun `after a dropped connection the next read starts a new download`() {
        val file = FakeFile(10_000)
        val reader = RangeReader(file::open)
        val buf = ByteArray(1000)
        reader.read(0, 1000, buf)
        file.failNextRead = true
        assertThrows(IOException::class.java) { reader.read(1000, 1000, buf) }
        assertEquals(1000, reader.read(1000, 1000, buf))
        assertEquals(listOf(0L, 1000L), file.opens)
        assertArrayEquals(file.bytes.copyOfRange(1000, 2000), buf)
    }
}
