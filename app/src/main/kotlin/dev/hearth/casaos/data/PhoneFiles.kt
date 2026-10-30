package dev.hearth.casaos.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A file picked on the phone for upload. */
data class PickedFile(val uri: Uri, val name: String, val size: Long)

/**
 * Moves files between the phone and the server: uploads files the user picked, and keeps temporary copies of
 * server files so another app can open them or they can be shared.
 */
class PhoneFiles(private val context: Context, private val client: CasaClient) {

    private val openDir get() = File(context.cacheDir, "open")

    /** Name and size of a picked file, or null if the phone can't read it. */
    suspend fun describe(uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file"
                val size = if (c.isNull(1)) -1L else c.getLong(1)
                PickedFile(uri, name.replace('/', '_'), size)
            }
        }.getOrNull()
    }

    /** Uploads [file] into the server folder [dir] as [name]. */
    suspend fun upload(file: PickedFile, dir: String, name: String, onProgress: (Long, Long) -> Unit) {
        // Some providers don't report a size; measure it, since the server needs it to split the chunks.
        val size = file.size.takeIf { it >= 0 } ?: withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(file.uri)?.use { s ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                }
                total
            }
        } ?: throw CasaException.Network("Couldn't read ${file.name}")
        val input = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(file.uri) }
            ?: throw CasaException.Network("Couldn't read ${file.name}")
        input.use { client.upload(dir, name, size, it, onProgress) }
    }

    /**
     * Downloads [entry] into Hearth's cache and returns a link other apps may read, for "Open with" and "Share".
     * Copies from earlier sessions are cleared first, so the cache doesn't grow with every file opened.
     */
    suspend fun fetchForSharing(entry: FileEntry, onProgress: (Long, Long) -> Unit): Uri {
        val target = withContext(Dispatchers.IO) {
            openDir.mkdirs()
            openDir.listFiles()?.forEach { it.deleteRecursively() }
            File(openDir, entry.name.replace('/', '_'))
        }
        try {
            withContext(Dispatchers.IO) { target.outputStream() }.use { client.download(entry.path, it, onProgress) }
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw t
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }

    companion object {
        /** Empties the cache of opened files; called when the user signs out of a server. */
        fun clearCache(context: Context) {
            runCatching { File(context.cacheDir, "open").deleteRecursively() }
        }
    }
}

fun mimeTypeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

/** What Hearth can show for a file itself, without another app. */
enum class PreviewKind { Image, Text, Video, Audio }

fun previewKind(name: String): PreviewKind? = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> PreviewKind.Image
    "mp4", "m4v", "mkv", "webm", "mov", "3gp" -> PreviewKind.Video
    "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus" -> PreviewKind.Audio
    "txt", "md", "log", "json", "yaml", "yml", "xml", "csv", "tsv", "conf", "cfg", "ini", "toml", "env",
    "properties", "sh", "py", "js", "ts", "kt", "java", "c", "h", "cpp", "go", "rs", "html", "css", "sql", "srt", "nfo",
    -> PreviewKind.Text
    else -> if (name.equals("Dockerfile", ignoreCase = true) || name.startsWith(".") && '.' !in name.drop(1)) PreviewKind.Text else null
}

/** "photo.jpg" → "photo (1).jpg", the first name not in [taken]. */
fun uniqueName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val base = name.substring(0, dot)
    val ext = name.substring(dot)
    var i = 1
    while ("$base ($i)$ext" in taken) i++
    return "$base ($i)$ext"
}
