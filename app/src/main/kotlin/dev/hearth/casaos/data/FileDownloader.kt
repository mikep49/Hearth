package dev.hearth.casaos.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap

/** Saves server files into the public Downloads/Hearth folder via MediaStore (no storage permission needed). */
class FileDownloader(private val context: Context, private val client: CasaClient) {

    /** Returns the saved file's Uri. Deletes the partial file if the transfer fails or is cancelled. */
    suspend fun save(entry: FileEntry, onProgress: (Long, Long) -> Unit): Uri {
        val resolver = context.contentResolver
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, entry.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Hearth")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw CasaException.Network("Couldn't create the file in Downloads")
        try {
            val out = resolver.openOutputStream(uri) ?: throw CasaException.Network("Couldn't write to Downloads")
            out.use { client.download(entry.path, it, onProgress) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (t: Throwable) {
            // Best effort: a failed cleanup must not hide the original error.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }
}
