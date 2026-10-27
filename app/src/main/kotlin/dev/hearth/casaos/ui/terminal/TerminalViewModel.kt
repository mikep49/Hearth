package dev.hearth.casaos.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.hearth.casaos.data.HostKeyInfo
import dev.hearth.casaos.data.Session
import dev.hearth.casaos.data.SshException
import dev.hearth.casaos.data.SshProfile
import dev.hearth.casaos.data.SshProfileStore
import dev.hearth.casaos.data.SshShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

data class SshForm(
    val host: String,
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val remember: Boolean = false,
) {
    val portNumber: Int? get() = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val canSubmit: Boolean get() = host.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && portNumber != null
}

data class HostKeyPrompt(val key: HostKeyInfo, val changed: Boolean)

data class TerminalUiState(
    val form: SshForm,
    val connecting: Boolean = false,
    val error: String? = null,
    val hostKeyPrompt: HostKeyPrompt? = null,
    /** A shell is attached (or was, see [ended]): show the terminal instead of the form. */
    val open: Boolean = false,
    /** Why the shell stopped, while its last screen stays visible. */
    val ended: String? = null,
)

/** Updates the server's Ubuntu/Debian packages. `&&` so the upgrade only runs once the package lists refreshed. */
const val SYSTEM_UPDATE_COMMAND = "sudo apt update && sudo apt upgrade -y"

/**
 * Holds the SSH shell and the terminal emulator. It is scoped to the signed-in account, so the shell
 * keeps running while the user switches tabs. All emulator access happens on the main thread.
 */
class TerminalViewModel(
    private val profiles: SshProfileStore,
    private val owner: Session,
    sessions: StateFlow<Session?>,
) : ViewModel() {
    private val server = owner.baseUrl.toHttpUrl().host

    private val _state = MutableStateFlow(TerminalUiState(form = profiles.load(server).toForm()))
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    var emulator by mutableStateOf<TerminalEmulator?>(null)
        private set

    /** Bumped whenever the screen content changes; read during drawing so only the canvas redraws. */
    var revision by mutableIntStateOf(0)
        private set

    /** 0 shows the live screen; negative values scroll back into history. */
    var topRow by mutableIntStateOf(0)
        private set

    private var shell: SshShell? = null
    private var outgoing: Channel<ByteArray>? = null
    private var connectJob: Job? = null
    private var ioJob: Job? = null

    private var columns = 80
    private var rows = 24
    private var cellWidth = 1
    private var cellHeight = 1

    init {
        // Signing out (or switching servers) must never leave a shell open in the background.
        viewModelScope.launch {
            sessions.collect { if (it != owner) close(clearPassword = true) }
        }
    }

    // region form

    fun onHost(v: String) = editForm { it.copy(host = v) }
    fun onPort(v: String) = editForm { it.copy(port = v.filter(Char::isDigit).take(5)) }
    fun onUsername(v: String) = editForm { it.copy(username = v) }
    fun onPassword(v: String) = editForm { it.copy(password = v) }
    fun onRemember(v: Boolean) = editForm { it.copy(remember = v) }

    private fun editForm(f: (SshForm) -> SshForm) = _state.update { it.copy(form = f(it.form), error = null) }

    // endregion

    // region connection

    fun connect() {
        val form = _state.value.form
        val port = form.portNumber ?: return
        if (!form.canSubmit || _state.value.connecting) return
        _state.update { it.copy(connecting = true, error = null) }
        connectJob = viewModelScope.launch {
            val opened = try {
                SshShell.open(form.host.trim(), port, form.username.trim(), form.password, profiles, columns, rows)
            } catch (e: SshException.UnknownHost) {
                _state.update { it.copy(connecting = false, hostKeyPrompt = HostKeyPrompt(e.key, changed = false)) }
                return@launch
            } catch (e: SshException.HostKeyChanged) {
                _state.update { it.copy(connecting = false, hostKeyPrompt = HostKeyPrompt(e.key, changed = true)) }
                return@launch
            } catch (e: SshException) {
                _state.update { it.copy(connecting = false, error = e.message) }
                return@launch
            }
            profiles.save(
                server,
                SshProfile(form.host.trim(), port, form.username.trim(), form.password.takeIf { form.remember }),
            )
            attach(opened)
        }
    }

    /** The user checked the fingerprint and accepts it. */
    fun trustHostKey() {
        val prompt = _state.value.hostKeyPrompt ?: return
        profiles.trust(prompt.key.id, prompt.key.keyBase64)
        _state.update { it.copy(hostKeyPrompt = null) }
        connect()
    }

    fun dismissHostKey() = _state.update {
        it.copy(hostKeyPrompt = null, error = if (it.hostKeyPrompt?.changed == true) "Not connected: the server's identity couldn't be confirmed" else null)
    }

    private fun attach(opened: SshShell) {
        shell = opened
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        outgoing = queue
        val emu = TerminalEmulator(Output(queue), columns, rows, cellWidth, cellHeight, SCROLLBACK_ROWS, NoopClient)
        emulator = emu
        topRow = 0
        _state.update { it.copy(connecting = false, open = true, ended = null, error = null) }

        ioJob = viewModelScope.launch {
            val writer = launch(Dispatchers.IO) {
                try {
                    for (bytes in queue) opened.write(bytes)
                } catch (_: IOException) {
                    // the reader notices the closed shell and reports it
                }
            }
            val buffer = ByteArray(16 * 1024)
            var reason = "Session ended"
            try {
                while (true) {
                    val n = withContext(Dispatchers.IO) { opened.read(buffer) }
                    if (n < 0) break
                    emu.append(buffer, n)
                    followOutput(emu)
                    revision++
                }
            } catch (_: IOException) {
                reason = "Connection lost"
            }
            writer.cancel()
            withContext(Dispatchers.IO) { opened.close() }
            if (shell === opened) {
                shell = null
                outgoing = null // typing into the ended session goes nowhere
                _state.update { it.copy(ended = reason) }
            }
        }
    }

    /** Keeps a scrolled-back view on the same lines while new output pushes the screen up. */
    private fun followOutput(emu: TerminalEmulator) {
        if (topRow != 0) {
            topRow = (topRow - emu.scrollCounter).coerceIn(-emu.screen.activeTranscriptRows, 0)
        }
        emu.clearScrollCounter()
    }

    /** Back to the sign-in form. */
    fun disconnect() = close(clearPassword = false)

    private fun close(clearPassword: Boolean) {
        connectJob?.cancel()
        ioJob?.cancel()
        val s = shell
        shell = null
        outgoing?.close()
        outgoing = null
        if (s != null) viewModelScope.launch(Dispatchers.IO) { s.close() }
        emulator = null
        _state.update {
            it.copy(
                connecting = false,
                open = false,
                ended = null,
                hostKeyPrompt = null,
                form = if (clearPassword) it.form.copy(password = "", remember = false) else it.form,
            )
        }
    }

    override fun onCleared() {
        shell?.let { s -> Thread { s.close() }.start() } // viewModelScope is already cancelled here
        shell = null
    }

    // endregion

    // region terminal I/O

    /** Called when the terminal view is laid out or its font size changes. */
    fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        if (columns < 4 || rows < 2) return
        if (columns == this.columns && rows == this.rows && cellWidth == this.cellWidth && cellHeight == this.cellHeight) return
        this.columns = columns
        this.rows = rows
        this.cellWidth = cellWidth
        this.cellHeight = cellHeight
        emulator?.let {
            it.resize(columns, rows, cellWidth, cellHeight)
            topRow = topRow.coerceIn(-it.screen.activeTranscriptRows, 0)
            revision++
        }
        val s = shell ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { s.resize(columns, rows, columns * cellWidth, rows * cellHeight) } }
    }

    /** Sends typed input. Jumps back to the live screen, as terminals do on a keypress. */
    fun send(text: String) {
        if (text.isEmpty()) return
        send(text.toByteArray(Charsets.UTF_8))
    }

    private fun send(bytes: ByteArray) {
        val queue = outgoing ?: return
        queue.trySend(bytes)
        if (topRow != 0) topRow = 0
    }

    /** Pastes via the emulator so bracketed-paste mode is honored. */
    fun paste(text: String) {
        emulator?.paste(text) ?: return
        topRow = 0
    }

    fun scrollBy(lines: Int) {
        val emu = emulator ?: return
        if (emu.isAlternateBufferActive) return // full-screen apps (nano, htop) have no history to scroll
        val next = (topRow + lines).coerceIn(-emu.screen.activeTranscriptRows, 0)
        if (next != topRow) topRow = next
    }

    fun runCommand(command: String) = send(command + "\r")

    // endregion

    /** Emulator replies (cursor position reports and the like) go straight back to the server. */
    private class Output(private val queue: Channel<ByteArray>) : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            queue.trySend(data.copyOfRange(offset, offset + count))
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit

        // A remote program must not read or overwrite the phone's clipboard (OSC 52).
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private object NoopClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private companion object {
        const val SCROLLBACK_ROWS = 3000
    }
}

private fun SshProfile.toForm() = SshForm(
    host = host,
    port = port.toString(),
    username = username,
    password = password.orEmpty(),
    remember = password != null,
)
