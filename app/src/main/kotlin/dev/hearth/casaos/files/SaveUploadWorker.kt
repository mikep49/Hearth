package dev.hearth.casaos.files

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.hearth.casaos.HearthApp
import dev.hearth.casaos.data.mimeTypeOf
import java.io.File

/**
 * Sees that what other apps saved through Android Files reaches the server even if Android ends Hearth first:
 * Android starts Hearth again for this, and leaves it running while it works. The uploading itself is in
 * [dev.hearth.casaos.data.SavedUploads], which carries on if this is stopped.
 */
class SaveUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val saves = (applicationContext as HearthApp).container.saves
        // Trying again covers a save that could go neither to the server nor into Downloads.
        return if (saves.finish()) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK = "saved-uploads"

        /** Call when a save starts waiting. Added after a run already under way, which may be past noticing it. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SaveUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Copies [file] into Downloads/Hearth on the phone as [name]. Returns whether that worked. */
        fun keepOnPhone(context: Context, name: String, file: File): Boolean {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeTypeOf(name))
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Hearth")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return false
            return try {
                val out = resolver.openOutputStream(uri) ?: error("Couldn't write to Downloads")
                out.use { o -> file.inputStream().use { it.copyTo(o) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                true
            } catch (e: Exception) {
                // Half a copy would look like the save, so it goes. The file itself stays with Hearth for another try.
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        }
    }
}
