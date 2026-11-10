package dev.hearth.casaos.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A download, upload or "open with" in progress. Only one runs at a time. */
data class Transfer(val label: String, /** 0..1, or null when the total size is unknown. */ val progress: Float? = null)

/** Lets a running transfer say how far it has got. */
class TransferProgress internal constructor(private val current: MutableStateFlow<Transfer?>) {
    /** Starts the next step, e.g. the next file of several, with its progress back at the start. */
    fun label(text: String) = current.update { it?.let { Transfer(text) } }

    fun update(done: Long, total: Long) =
        current.update { it?.copy(progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null) }
}

/**
 * Runs downloads and uploads for the whole app rather than for one screen, so a transfer carries on when its
 * screen is closed. [keepAlive] is called as each one starts, to keep Android from pausing Hearth while it runs
 * in the background. [onOutcome] gets what a finished transfer had to say, unless the user cancelled it.
 */
class Transfers(
    private val scope: CoroutineScope,
    private val keepAlive: () -> Unit = {},
    private val onOutcome: (String) -> Unit = {},
) {
    private val _current = MutableStateFlow<Transfer?>(null)
    val current: StateFlow<Transfer?> = _current.asStateFlow()

    private val progress = TransferProgress(_current)
    private var job: Job? = null
    private var stopReason: String? = null

    /**
     * Starts [work] unless another transfer is running, in which case it returns false. [work] returns what to
     * tell the user when it's done, if anything. That, or why it failed or that it was cancelled, goes to [onMessage].
     */
    fun start(
        label: String,
        failure: String,
        cancelled: String = "Cancelled",
        onMessage: (String) -> Unit,
        work: suspend (TransferProgress) -> String?,
    ): Boolean {
        if (!_current.compareAndSet(null, Transfer(label))) return false
        stopReason = null
        // Best effort: without it the transfer still runs for as long as Android leaves Hearth alone.
        runCatching(keepAlive)
        job = scope.launch {
            var byUser = false
            val message = try {
                work(progress)
            } catch (_: CancellationException) {
                byUser = stopReason == null
                stopReason ?: cancelled
            } catch (e: Exception) {
                // Storage can fail in several ways (disk full, MediaStore refusing the write, file removed
                // mid-transfer): show a message instead of crashing the app.
                "$failure: ${e.message ?: "storage error"}"
            } finally {
                _current.value = null
            }
            if (message != null) {
                onMessage(message)
                if (!byUser) onOutcome(message)
            }
        }
        return true
    }

    /** Stops the transfer under way, if any. [reason] says why when it wasn't the user's doing. */
    fun cancel(reason: String? = null) {
        if (_current.value == null) return
        stopReason = reason
        job?.cancel()
    }
}
