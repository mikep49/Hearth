package dev.hearth.casaos.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What other apps save through Android Files. An app writes into a file on the phone, and Hearth uploads it once
 * the app closes it. By then the app has been told its save worked, so the file and where it goes stay on disk
 * until the server has it, or failing that until a copy is somewhere the user can find it. Android can end Hearth
 * at any point in between, and the next run carries on with what's left.
 *
 * Each save is two files in [dir]: its contents, named `.part` while the app has it open and `.save` once it's
 * closed and waiting to go up, and a `.json` saying where it goes.
 */
class SavedUploads(
    private val dir: File,
    private val scope: CoroutineScope,
    /** Uploads the file to where the [DocumentRef] points, replacing what's there. */
    private val send: suspend (DocumentRef, File) -> Unit,
    /** Copies a file the server wouldn't take to somewhere the user can find it. Returns whether that worked. */
    private val keepOnPhone: (name: String, File) -> Boolean,
    private val onUploaded: (DocumentRef) -> Unit = {},
    /** [onPhone] is false when the copy failed too, so the save is still waiting here and will be tried again. */
    private val onFailed: (DocumentRef, reason: String, onPhone: Boolean) -> Unit = { _, _, _ -> },
    /** Asks Android to start Hearth again, and call [finish], if it ends Hearth before the uploads are done. */
    private val keepAlive: () -> Unit = {},
    private val retryDelayMs: Long = 2_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Where a save goes, and what its file looked like when the app was handed it. */
    @Serializable
    private data class Target(val server: String, val path: String, val replaces: Boolean, val modified: Long, val length: Long) {
        fun matches(file: File) = modified == file.lastModified() && length == file.length()
    }

    private class Save(val id: String, val ref: DocumentRef, val file: File, val target: Target) {
        val closed: Boolean get() = file.name.endsWith(SAVE)
    }

    /** Guards the files in [dir], and [job]. */
    private val lock = Any()
    private var job: Job? = null

    /** Files apps have open now. Any other `.part` was left open when Android ended an earlier run of Hearth. */
    private val open = ConcurrentHashMap.newKeySet<String>()

    /** Saves the user has been told are stuck, so each retry doesn't say it again. */
    private val told = ConcurrentHashMap.newKeySet<String>()

    /** Whether a wake-up that hasn't started yet is already asked for. */
    private val wakeAsked = AtomicBoolean()

    /** A new empty file for an app to write into. Follow with [opened], or [closed] if it never gets that far. */
    fun newFile(): File {
        val id = UUID.randomUUID().toString()
        open += id
        dir.mkdirs()
        return File(dir, id + PART).apply { createNewFile() }
    }

    /**
     * [file] is about to be handed to an app to save [ref] with, holding what the app starts from. With [replaces],
     * the app is writing the file anew, so closing it counts as a save even if it wrote nothing.
     */
    fun opened(file: File, ref: DocumentRef, replaces: Boolean) {
        val target = Target(ref.server, ref.path, replaces, file.lastModified(), file.length())
        File(dir, idOf(file) + INFO).writeText(json.encodeToString(Target.serializer(), target))
    }

    /**
     * The app closed [file]. With [failed], the app said its write went wrong, so nothing is uploaded; nor is a
     * file it opened to change and left as it was. Anything else is uploaded.
     */
    fun closed(file: File, failed: Boolean) {
        val id = idOf(file)
        val waiting = synchronized(lock) {
            try {
                val target = read(id)
                if (failed || target == null || !target.replaces && target.matches(file)) {
                    delete(id)
                    return@synchronized false
                }
                // If this fails the file is left as it is, and picked up as one a run of Hearth left open.
                file.renameTo(File(dir, id + SAVE))
                // It's now the newest save of that file, so older ones still waiting don't need to go up.
                val ref = DocumentRef(target.server, target.path)
                saves().filter { it.ref == ref && it.id != id && it.id !in open }.forEach { delete(it.id) }
                true
            } finally {
                open -= id
            }
        }
        if (waiting) start()
    }

    /** Call when Hearth starts: carries on with what the last run didn't finish. */
    fun resume() {
        if (unfinished()) start()
    }

    /**
     * For the wake-up [keepAlive] asked for: uploads what's waiting, and returns whether everything is done.
     * Stopping the caller doesn't stop the uploads.
     */
    suspend fun finish(): Boolean {
        // From here on a save needs a wake-up of its own, since this one may already be past it.
        wakeAsked.set(false)
        upload().join()
        return !unfinished()
    }

    /** Whether any save is still on the phone only, other than files apps have open now. */
    fun unfinished(): Boolean = synchronized(lock) { leftovers().isNotEmpty() }

    private fun start() {
        // Best effort: without it the uploads still run for as long as Android leaves Hearth alone.
        if (wakeAsked.compareAndSet(false, true)) runCatching(keepAlive).onFailure { wakeAsked.set(false) }
        upload()
    }

    /** Uploads whatever is waiting, oldest first, unless that's already under way. */
    private fun upload(): Job = synchronized(lock) {
        job?.takeIf { it.isActive } ?: scope.launch { drain() }.also { job = it }
    }

    private suspend fun drain() {
        val stuck = HashSet<String>()
        while (true) {
            // Looking and stopping happen together, so a save closed meanwhile is either seen here or starts a new round.
            val save = synchronized(lock) {
                leftovers().filter { it.closed && it.id !in stuck }.minWithOrNull(compareBy<Save>({ it.file.lastModified() }, { it.id }))
                    ?: run { job = null; return }
            }
            if (!deliver(save)) stuck += save.id
        }
    }

    /** Gets [save] to the server, or failing that onto the phone. False when it's in neither, so it stays for a later try. */
    private suspend fun deliver(save: Save): Boolean {
        val failure = trySending(save)
        if (failure == null) {
            synchronized(lock) { delete(save.id) }
            runCatching { onUploaded(save.ref) }
            return true
        }
        // Replaced by a newer save of the same file while it was going up.
        if (!save.file.exists()) return true
        val onPhone = runCatching { keepOnPhone(save.ref.name, save.file) }.getOrDefault(false)
        if (onPhone) synchronized(lock) { delete(save.id) }
        if (onPhone || told.add(save.id)) runCatching { onFailed(save.ref, failure.message ?: "upload failed", onPhone) }
        return onPhone
    }

    /** Tries the upload a few times. Returns why it failed, or null when the server has the file. */
    private suspend fun trySending(save: Save): Exception? {
        var failure: Exception? = null
        for (attempt in 1..UPLOAD_TRIES) {
            failure = try {
                send(save.ref, save.file)
                return null
            } catch (e: CancellationException) {
                throw e // Hearth is stopping; the save stays where it is
            } catch (e: CasaException) {
                e
            } catch (e: Exception) {
                return e // the phone's copy couldn't be read, which trying again won't fix
            }
            if (failure is CasaException.Unauthorized || attempt == UPLOAD_TRIES) break
            delay(retryDelayMs * attempt)
        }
        return failure
    }

    /**
     * Every save except those apps have open now, after sorting out what an earlier run of Hearth left open.
     * Hearth never saw those closed, so it can't know the app finished: one that hasn't changed since it was
     * opened is dropped, and one that has is taken as saved once it's been left alone for a while.
     */
    private fun leftovers(): List<Save> {
        val saves = saves().filter { it.id !in open }
        for (s in saves) {
            if (s.closed) continue
            if (!s.file.exists() || s.target.matches(s.file)) delete(s.id)
            else if (now() - s.file.lastModified() >= LEFT_OPEN_QUIET_MS) s.file.renameTo(File(dir, s.id + SAVE))
        }
        // Files with no note of where they go (or the other way round) can't be uploaded.
        val known = saves.mapTo(HashSet()) { it.id }
        dir.listFiles().orEmpty().map { it.name.substringBeforeLast('.') }.filter { it !in known && it !in open }.forEach(::delete)
        return saves().filter { it.id !in open }
    }

    private fun saves(): List<Save> = dir.listFiles().orEmpty().filter { it.name.endsWith(INFO) }.mapNotNull { info ->
        val id = info.name.removeSuffix(INFO)
        val target = read(id) ?: return@mapNotNull null
        val file = File(dir, id + SAVE).takeIf { it.exists() } ?: File(dir, id + PART)
        Save(id, DocumentRef(target.server, target.path), file, target)
    }

    private fun read(id: String): Target? =
        runCatching { json.decodeFromString(Target.serializer(), File(dir, id + INFO).readText()) }.getOrNull()

    private fun delete(id: String) {
        for (suffix in arrayOf(PART, SAVE, INFO)) File(dir, id + suffix).delete()
        told -= id
    }

    private fun idOf(file: File) = file.name.substringBeforeLast('.')

    private companion object {
        const val PART = ".part"
        const val SAVE = ".save"
        const val INFO = ".json"
        const val UPLOAD_TRIES = 3

        /** How long a file an earlier run left open must go unwritten before it's taken as finished. */
        const val LEFT_OPEN_QUIET_MS = 2 * 60 * 1000L

        val json = Json { ignoreUnknownKeys = true }
    }
}
