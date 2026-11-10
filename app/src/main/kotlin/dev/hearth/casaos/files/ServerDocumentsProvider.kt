package dev.hearth.casaos.files

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Point
import android.media.ExifInterface
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import dev.hearth.casaos.AppContainer
import dev.hearth.casaos.HearthApp
import dev.hearth.casaos.R
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.DocumentRef
import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.RangeReader
import dev.hearth.casaos.data.cleanFileName
import dev.hearth.casaos.data.isHidden
import dev.hearth.casaos.data.mimeTypeOf
import dev.hearth.casaos.data.modifiedMillis
import dev.hearth.casaos.data.rootSummaries
import dev.hearth.casaos.data.serverLabel
import dev.hearth.casaos.data.uniqueName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Puts each signed-in server in Android's Files app, under "Hearth" in its side menu, and in the file pickers
 * other apps open. It's off until "Show in Android Files" is turned on in Settings, and never on while the app
 * lock is, since the Files app can't ask for the lock: the provider is disabled in the manifest and turned on
 * by [setShown].
 *
 * Android calls in on its own threads, so server calls block here. Reading streams the file in ranges, so a
 * video starts playing without downloading it first. Writing goes to a temporary file that's uploaded when
 * the app closes it.
 */
class ServerDocumentsProvider : DocumentsProvider() {

    /** ContentProvider.requireContext() only exists from Android 11, and Hearth runs on Android 10. */
    private val appContext: Context get() = checkNotNull(context) { "The provider isn't attached yet" }

    // Providers start before the Application, so the container is looked up on each call, not here.
    private val container: AppContainer get() = (appContext.applicationContext as HearthApp).container
    private val authority: String get() = authority(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Recently listed folders by document id, so looking up one item doesn't list its folder again. */
    private val listings = object : LinkedHashMap<String, List<FileEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FileEntry>>) = size > 64
    }

    /** New files the server doesn't have yet (only if making an empty one there failed), by document id. */
    private val pending = ConcurrentHashMap<String, FileEntry>()

    /** Thumbnails download the whole photo, so only a few at once. */
    private val thumbnailSlots = Semaphore(2)

    /** Runs the callbacks for files other apps close after writing. */
    private val closeHandler by lazy { Handler(HandlerThread("hearth-files-close").apply { start() }.looper) }

    override fun onCreate(): Boolean {
        // Left over from writes Android cut short when it ended Hearth. Those uploads can't finish now.
        runCatching { uploadDir(appContext).deleteRecursively() }
        return true
    }

    // region browsing

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        cursor.setNotificationUri(appContext.contentResolver, DocumentsContract.buildRootsUri(authority))
        if (!shown()) return cursor
        val servers = container.session.profiles.value.filter { it.signedIn }
        val summaries = rootSummaries(servers)
        for (server in servers) {
            cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, server.id)
                .add(Root.COLUMN_DOCUMENT_ID, DocumentRef.root(server.id).id)
                .add(Root.COLUMN_TITLE, appContext.getString(R.string.app_name))
                .add(Root.COLUMN_SUMMARY, summaries[server.id])
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val ref = ref(documentId)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (ref.isRoot) {
            val server = container.session.profile(ref.server)
            cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, ref.id)
                .add(Document.COLUMN_DISPLAY_NAME, server?.let { serverLabel(it.baseUrl) } ?: ref.name)
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
        } else {
            cursor.addEntry(ref, entry(ref))
        }
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val folder = ref(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val entries = try {
            list(folder)
        } catch (e: FileNotFoundException) {
            // The Files app shows this above the folder. What was there last time is better than nothing.
            cursor.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, e.message ?: "Couldn't reach the server") }
            synchronized(listings) { listings[folder.id] }.orEmpty()
        }
        val names = entries.mapTo(HashSet()) { it.name }
        val waiting = pending.filterKeys { DocumentRef.parse(it)?.parent() == folder }.values.filter { it.name !in names }
        // Same choice as Hearth's own Files tab.
        val showHidden = container.prefs.fileSort.value.showHidden
        for (e in entries + waiting) {
            if (showHidden || !isHidden(e.name)) cursor.addEntry(folder.child(e.name), e)
        }
        cursor.setNotificationUri(appContext.contentResolver, DocumentsContract.buildChildDocumentsUri(authority, folder.id))
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = DocumentRef.parse(parentDocumentId) ?: return false
        val child = DocumentRef.parse(documentId) ?: return false
        return shown() && parent.contains(child)
    }

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        val child = ref(childDocumentId)
        val top = parentDocumentId?.let(::ref) ?: DocumentRef.root(child.server)
        if (top != child && !top.contains(child)) throw FileNotFoundException("${child.name} isn't in that folder")
        val path = generateSequence(child) { if (it == top) null else it.parent() }.map { it.id }.toList().asReversed()
        return DocumentsContract.Path(if (parentDocumentId == null) child.server else null, path)
    }

    // endregion

    // region changes

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val folder = ref(parentDocumentId)
        val isDir = mimeType == Document.MIME_TYPE_DIR
        val clean = cleanFileName(displayName) ?: throw IllegalArgumentException("That name can't be used")
        val taken = list(folder).mapTo(HashSet()) { it.name } + pendingNames(folder)
        val ref = folder.child(uniqueName(if (isDir) clean else withExtension(clean, mimeType), taken))
        if (isDir) {
            blocking { client(ref).createFolder(ref.path) }
        } else {
            // Made empty right away so it's there when the app looks for it. Its contents come when the app writes them.
            val made = runCatching { blocking { client(ref).upload(folder.path, ref.name, 0, ByteArrayInputStream(ByteArray(0))) } }
            if (made.isFailure) pending[ref.id] = FileEntry(ref.name, ref.path, isDir = false, size = 0, modified = null)
        }
        changed(folder)
        return ref.id
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val ref = ref(documentId)
        if (ref.isRoot) throw UnsupportedOperationException("The top folder can't be renamed")
        val name = cleanFileName(displayName) ?: throw IllegalArgumentException("That name can't be used")
        val folder = ref.parent()
        val target = folder.child(name)
        if (target == ref) return null
        if (list(folder).any { it.name == name } || name in pendingNames(folder)) throw IllegalStateException("$name already exists")
        val waiting = pending.remove(ref.id)
        if (waiting != null) pending[target.id] = waiting.copy(name = name, path = target.path)
        else blocking { client(ref).rename(ref.path, target.path) }
        changed(folder, ref)
        return target.id
    }

    override fun deleteDocument(documentId: String) {
        val ref = ref(documentId)
        if (ref.isRoot) throw UnsupportedOperationException("The top folder can't be deleted")
        if (pending.remove(ref.id) == null) blocking { client(ref).delete(listOf(ref.path)) }
        changed(ref.parent(), ref)
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String =
        transfer(sourceDocumentId, targetParentDocumentId, move = false)

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String =
        transfer(sourceDocumentId, targetParentDocumentId, move = true)

    /**
     * Copies or moves on the server itself, so nothing goes through the phone. When this refuses (another
     * server, or the name is taken there), the Files app copies through the phone instead.
     */
    private fun transfer(sourceId: String, targetId: String, move: Boolean): String {
        val source = ref(sourceId)
        val target = ref(targetId)
        if (source.server != target.server || pending.containsKey(source.id)) throw UnsupportedOperationException("Copied through the phone")
        if (source.isRoot || source == target || source.contains(target)) throw IllegalArgumentException("A folder can't go inside itself")
        if (move && source.parent() == target) return source.id
        if (list(target).any { it.name == source.name }) throw IllegalStateException("${source.name} is already there")
        blocking { client(source).transfer(listOf(source.path), target.path, move) }
        val touched = if (move) arrayOf(target, source.parent(), source) else arrayOf(target)
        changed(*touched)
        // The server finishes in the background, so look again once it's had time to.
        scope.launch {
            for (wait in longArrayOf(1_500, 5_000)) {
                delay(wait)
                changed(*touched)
            }
        }
        return target.child(source.name).id
    }

    // endregion

    // region contents

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val ref = ref(documentId)
        if (ref.isRoot) throw FileNotFoundException("That's a folder")
        return if ('w' in mode) openForWriting(ref, mode, signal) else openForReading(ref)
    }

    /**
     * A file descriptor backed by the server: each read asks for just the part the app wants, so seeking in a
     * video or PDF doesn't wait for the whole file.
     */
    private fun openForReading(ref: DocumentRef): ParcelFileDescriptor {
        val entry = entry(ref)
        if (entry.isDir) throw FileNotFoundException("${ref.name} is a folder")
        val client = client(ref)
        val reader = RangeReader { offset -> client.openFrom(ref.path, offset) }
        val thread = HandlerThread("hearth-read").apply { start() }
        val storage = appContext.getSystemService(StorageManager::class.java)
        return try {
            storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = entry.size

                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                        reader.read(offset, size, data)
                    } catch (e: Exception) {
                        Log.w(TAG, "Reading ${ref.name} failed", e)
                        throw ErrnoException("onRead", OsConstants.EIO)
                    }

                    override fun onRelease() {
                        reader.close()
                        thread.quitSafely()
                    }
                },
                Handler(thread.looper),
            )
        } catch (e: IOException) {
            thread.quitSafely()
            throw FileNotFoundException(e.message)
        }
    }

    /** A temporary file the app writes to, uploaded when the app closes it. */
    private fun openForWriting(ref: DocumentRef, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val flags = ParcelFileDescriptor.parseMode(mode)
        val truncate = flags and ParcelFileDescriptor.MODE_TRUNCATE != 0
        // Opened to change or add to the file, it has to be found first: treating it as new after a dropped
        // connection would upload over it with only what the app added. Replacing it can go ahead regardless.
        val existing = if (truncate) runCatching { entry(ref) }.getOrNull() else entry(ref)
        if (existing?.isDir == true) throw FileNotFoundException("${ref.name} is a folder")
        val file = File.createTempFile("upload", null, uploadDir(appContext).apply { mkdirs() })
        try {
            // The app starts from the current contents.
            if (!truncate && existing != null && existing.size > 0) {
                blocking(signal) { file.outputStream().use { client(ref).download(ref.path, it) } }
            }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        val before = file.lastModified() to file.length()
        return ParcelFileDescriptor.open(file, flags, closeHandler) { error ->
            when {
                error != null -> {
                    Log.w(TAG, "The app writing ${ref.name} failed, so it isn't uploaded", error)
                    file.delete()
                }
                !truncate && before == (file.lastModified() to file.length()) -> file.delete() // nothing changed
                else -> scope.launch { upload(ref, file) }
            }
        }
    }

    /**
     * Uploads what an app saved, trying a few times. If the server still won't take it, the app already thinks
     * it's saved, so it goes to Downloads on the phone rather than being lost, and a message says so.
     */
    private suspend fun upload(ref: DocumentRef, file: File) {
        val folder = ref.parent()
        try {
            var tries = 0
            while (true) {
                try {
                    file.inputStream().use { client(ref).upload(folder.path, ref.name, file.length(), it) }
                    break
                } catch (e: CasaException) {
                    if (++tries >= UPLOAD_TRIES || e is CasaException.Unauthorized) throw e
                    delay(2_000L * tries)
                }
            }
            pending.remove(ref.id)
            changed(folder)
        } catch (e: CasaException) {
            Log.w(TAG, "Uploading ${ref.name} failed", e)
            val server = container.session.profile(ref.server)?.let { serverLabel(it.baseUrl) } ?: "the server"
            val kept = keepOnPhone(ref.name, file)
            val message = "Couldn't save ${ref.name} to $server: ${e.message}" + if (kept) ". A copy is in Downloads/Hearth." else ""
            withContext(Dispatchers.Main) { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
        } finally {
            file.delete()
        }
    }

    /** Copies [file] into Downloads/Hearth on the phone. Returns whether that worked. */
    private fun keepOnPhone(name: String, file: File): Boolean = runCatching {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeOf(name))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Hearth")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        val out = resolver.openOutputStream(uri)
        if (out == null) {
            resolver.delete(uri, null, null)
            return false
        }
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
        true
    }.getOrDefault(false)

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        val ref = ref(documentId)
        val entry = entry(ref)
        if (entry.isDir || entry.size !in 1..THUMBNAIL_MAX_BYTES) throw FileNotFoundException("No thumbnail")
        val dir = thumbnailDir(appContext).apply { mkdirs() }
        val file = File(dir, sha1("${ref.id}|${entry.size}|${entry.modified}"))
        if (!file.exists()) {
            thumbnailSlots.acquire()
            try {
                if (!file.exists()) makeThumbnail(ref, entry, sizeHint, file, signal)
            } finally {
                thumbnailSlots.release()
            }
        }
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    /** CasaOS has no thumbnails, so this downloads the photo and shrinks it to about [sizeHint]. */
    private fun makeThumbnail(ref: DocumentRef, entry: FileEntry, sizeHint: Point, file: File, signal: CancellationSignal?) {
        val bytes = blocking(signal) { client(ref).readStart(ref.path, entry.size.toInt()) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw FileNotFoundException("Not an image")
        val want = maxOf(sizeHint.x, sizeHint.y, 1)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= want) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw FileNotFoundException("Not an image")
        val degrees = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val bitmap = if (degrees == 0f) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
        val dir = file.parentFile ?: return
        val temp = File.createTempFile("thumb", null, dir)
        try {
            temp.outputStream().use {
                // PNG keeps transparency; photos are much smaller as JPEG.
                bitmap.compress(if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, it)
            }
            if (!temp.renameTo(file)) throw FileNotFoundException("Couldn't save the thumbnail")
        } finally {
            temp.delete()
        }
        // Keep the newest few hundred; Android can also clear them when the phone runs low on space.
        val all = dir.listFiles().orEmpty()
        if (all.size > THUMBNAILS_KEPT) all.sortedBy { it.lastModified() }.take(all.size - THUMBNAILS_KEPT).forEach { it.delete() }
    }

    // endregion

    // region plumbing

    private fun shown(): Boolean = container.prefs.showInFiles.value && !container.lock.enabled.value

    /** The item [id] names, as long as Hearth is in the Files app and that server is signed in. */
    private fun ref(id: String): DocumentRef {
        val ref = DocumentRef.parse(id) ?: throw FileNotFoundException("No such file")
        if (!shown()) throw FileNotFoundException("Show in Android Files is off in Hearth")
        if (container.session.profiles.value.none { it.id == ref.server && it.signedIn }) throw FileNotFoundException("Signed out of that server")
        return ref
    }

    private fun client(ref: DocumentRef): CasaClient = container.clientFor(ref.server)

    /** The item [ref] points at, from its folder's last listing if there is one. */
    private fun entry(ref: DocumentRef): FileEntry {
        pending[ref.id]?.let { return it }
        val folder = ref.parent()
        synchronized(listings) { listings[folder.id] }?.firstOrNull { it.name == ref.name }?.let { return it }
        return list(folder).firstOrNull { it.name == ref.name } ?: pending[ref.id]
            ?: throw FileNotFoundException("${ref.name} isn't on the server any more")
    }

    /** Lists [folder] on its server and remembers what's in it. */
    private fun list(folder: DocumentRef): List<FileEntry> {
        val entries = blocking { client(folder).folder(folder.path).entries }
        synchronized(listings) { listings[folder.id] = entries }
        return entries
    }

    private fun pendingNames(folder: DocumentRef): Set<String> =
        pending.filterKeys { DocumentRef.parse(it)?.parent() == folder }.values.mapTo(HashSet()) { it.name }

    /** Forgets what [folders] held and tells the Files app to show them again. */
    private fun changed(vararg folders: DocumentRef) {
        synchronized(listings) { folders.forEach { listings.remove(it.id) } }
        val resolver = appContext.contentResolver
        for (f in folders) resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, f.id), null)
    }

    private fun MatrixCursor.addEntry(ref: DocumentRef, e: FileEntry) {
        val mime = if (e.isDir) Document.MIME_TYPE_DIR else mimeTypeOf(e.name)
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
            Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_COPY
        flags = flags or if (e.isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (!e.isDir && mime.startsWith("image/") && e.size in 1..THUMBNAIL_MAX_BYTES) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, ref.id)
            .add(Document.COLUMN_DISPLAY_NAME, e.name)
            .add(Document.COLUMN_MIME_TYPE, mime)
            .add(Document.COLUMN_SIZE, if (e.isDir) null else e.size)
            .add(Document.COLUMN_LAST_MODIFIED, e.modifiedMillis())
            .add(Document.COLUMN_FLAGS, flags)
    }

    /** "photo" saved as image/jpeg becomes "photo.jpg", the way the phone's own storage names it. */
    private fun withExtension(name: String, mimeType: String): String {
        val types = MimeTypeMap.getSingleton()
        val ext = name.substringAfterLast('.', "").lowercase()
        if (mimeType == "application/octet-stream" || ext.isNotEmpty() && types.getMimeTypeFromExtension(ext) == mimeType) return name
        val wanted = types.getExtensionFromMimeType(mimeType) ?: return name
        return "$name.$wanted"
    }

    /** Runs a server call on Android's thread, cancelled with [signal], failing the way Android expects. */
    private fun <T> blocking(signal: CancellationSignal? = null, block: suspend () -> T): T = try {
        runBlocking(Dispatchers.IO) {
            val job = coroutineContext.job
            signal?.setOnCancelListener { job.cancel() }
            block()
        }
    } catch (e: CasaException) {
        throw FileNotFoundException(e.message)
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: IOException) {
        throw FileNotFoundException(e.message)
    } catch (e: CancellationException) {
        throw OperationCanceledException()
    }

    // endregion

    companion object {
        private const val TAG = "HearthFiles"
        private const val THUMBNAIL_MAX_BYTES = 20L * 1024 * 1024
        private const val THUMBNAILS_KEPT = 300
        private const val UPLOAD_TRIES = 3

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_FLAGS,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        fun authority(context: Context) = "${context.packageName}.documents"

        /**
         * Puts Hearth in the Files app, or takes it out. Turning the provider itself on and off means Android
         * doesn't even start Hearth for it while it's off, and links other apps saved stop working.
         */
        fun setShown(context: Context, shown: Boolean) {
            val pm = context.packageManager
            val component = ComponentName(context, ServerDocumentsProvider::class.java)
            val isOn = pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (isOn != shown) {
                val state = if (shown) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
            if (!shown) clearCache(context)
            serversChanged(context)
        }

        /** Tells the Files app to look again at which servers to show, e.g. after signing in or out. */
        fun serversChanged(context: Context) {
            context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(context)), null)
        }

        /** Removes saved thumbnails; called on sign-out and when Hearth leaves the Files app. */
        fun clearCache(context: Context) {
            runCatching { thumbnailDir(context).deleteRecursively() }
        }

        private fun thumbnailDir(context: Context) = File(context.cacheDir, "documents/thumbnails")
        private fun uploadDir(context: Context) = File(context.cacheDir, "documents/uploads")

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
