package dev.hearth.casaos.data

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime

/*
 * The parts of "Show in Android Files" that don't need Android: naming files and folders for the Files app,
 * and reading a server file at any offset. The provider itself is files/ServerDocumentsProvider.kt.
 */

/**
 * A file or folder as the Files app knows it: [path] on the saved server [server]. Its [id] is what Android
 * stores in links to it, so it must never point outside the folder an app was given (see [parse]).
 */
data class DocumentRef(val server: String, val path: String) {
    val id: String get() = "$server:$path"
    val isRoot: Boolean get() = path == ROOT
    val name: String get() = path.substringAfterLast('/')

    fun parent(): DocumentRef = DocumentRef(server, path.substringBeforeLast('/').ifEmpty { "/" })

    fun child(name: String): DocumentRef = DocumentRef(server, path.trimEnd('/') + "/" + name)

    /** True when [other] is inside this folder, at any depth. */
    fun contains(other: DocumentRef): Boolean = other.server == server && other.path.startsWith(path.trimEnd('/') + "/")

    companion object {
        /** The folder the Files app opens on, the same one Hearth's Files tab starts in. */
        const val ROOT = "/DATA"

        fun root(server: String) = DocumentRef(server, ROOT)

        /**
         * Reads an [id] made by [DocumentRef.id]. Refuses anything outside [ROOT] and any `.` or `..` part, so an
         * app given one folder can't reach the rest of the server with a made-up link.
         */
        fun parse(id: String): DocumentRef? {
            val colon = id.indexOf(':')
            if (colon <= 0) return null
            val path = id.substring(colon + 1)
            if (path != ROOT && !path.startsWith("$ROOT/")) return null
            if (path.split('/').drop(1).any { it.isEmpty() || it == "." || it == ".." }) return null
            return DocumentRef(id.substring(0, colon), path)
        }
    }
}

/** A name the server will take for a new or renamed item: no slashes, and never `.` or `..`. Null when nothing's left. */
fun cleanFileName(name: String): String? =
    name.replace('/', '_').replace('\u0000', '_').trim().takeUnless { it.isEmpty() || it == "." || it == ".." }

/**
 * What the Files app shows under "Hearth" for each server: its address, plus the username when two signed-in
 * accounts share an address.
 */
fun rootSummaries(servers: List<ServerProfile>): Map<String, String> {
    val labels = servers.associate { it.id to serverLabel(it.baseUrl) }
    val shared = labels.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return servers.associate { p ->
        val label = labels.getValue(p.id)
        p.id to if (label in shared && p.username.isNotBlank()) "${p.username}@$label" else label
    }
}

/** When the item last changed, in milliseconds, or null if the server didn't say. */
fun FileEntry.modifiedMillis(): Long? {
    val text = modified ?: return null
    return runCatching { OffsetDateTime.parse(text).toInstant() }
        .recoverCatching { Instant.parse(text) }
        .getOrNull()?.toEpochMilli()
}

/** Skips up to [count] bytes, reading where [InputStream.skip] won't. Returns how many were skipped. */
fun skipFully(input: InputStream, count: Long): Long {
    var left = count
    val buf by lazy { ByteArray(64 * 1024) }
    while (left > 0) {
        val skipped = input.skip(left)
        if (skipped > 0) {
            left -= skipped
            continue
        }
        // skip() may return 0 before the end; a read tells the end apart.
        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (n < 0) break
        left -= n
    }
    return count - left
}

/**
 * Reads a server file at any offset, for a file descriptor another app can seek in. Reading on from where the
 * last read ended keeps using the open download; a short jump forward skips ahead in it; anything else starts
 * a new download from the new offset with [open].
 */
class RangeReader(private val open: (offset: Long) -> InputStream) : Closeable {
    private var stream: InputStream? = null
    private var position = 0L

    /** Fills [data] with up to [size] bytes from [offset]. Returns fewer only at the end of the file. */
    @Synchronized
    fun read(offset: Long, size: Int, data: ByteArray): Int {
        try {
            var s = stream
            if (s != null && offset > position && offset - position <= SKIP_AHEAD) {
                position += skipFully(s, offset - position)
            }
            if (s == null || offset != position) {
                close()
                s = open(offset)
                stream = s
                position = offset
            }
            var n = 0
            while (n < size) {
                val r = s.read(data, n, size - n)
                if (r < 0) break
                n += r
            }
            position += n
            return n
        } catch (e: IOException) {
            close() // start afresh on the next read
            throw e
        }
    }

    @Synchronized
    override fun close() {
        runCatching { stream?.close() }
        stream = null
    }

    private companion object {
        /** About what a new request costs on a home network, so skipping is cheaper up to here. */
        const val SKIP_AHEAD = 512 * 1024L
    }
}
