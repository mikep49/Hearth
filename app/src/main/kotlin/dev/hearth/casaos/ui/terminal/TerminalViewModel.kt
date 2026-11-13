package dev.hearth.casaos.ui.terminal

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.hearth.casaos.data.HostKeyInfo
import dev.hearth.casaos.data.PrefsStore
import dev.hearth.casaos.data.Session
import dev.hearth.casaos.data.SshAuth
import dev.hearth.casaos.data.SshException
import dev.hearth.casaos.data.SshKey
import dev.hearth.casaos.data.SshKeyException
import dev.hearth.casaos.data.SshKeys
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
import kotlin.math.abs

/** What the user signs in with. */
enum class SshMethod(val label: String) { Password("Password"), Key("SSH key") }

data class SshForm(
    val host: String,
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val remember: Boolean = false,
    val method: SshMethod = SshMethod.Password,
    /** The key saved for this server, whichever [method] is picked. */
    val key: SshKey? = null,
) {
    val portNumber: Int? get() = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** What to sign in with, or null while the picked [method] has nothing to offer yet. */
    val auth: SshAuth?
        get() = when (method) {
            SshMethod.Password -> password.takeIf { it.isNotEmpty() }?.let(SshAuth::Password)
            SshMethod.Key -> key?.let(SshAuth::Key)
        }

    val canSubmit: Boolean get() = host.isNotBlank() && username.isNotBlank() && auth != null && portNumber != null
}

data class HostKeyPrompt(val key: HostKeyInfo, val changed: Boolean)

/** The dialog for bringing in a key made elsewhere. */
data class KeyImport(val busy: Boolean = false, val error: String? = null)

data class TerminalUiState(
    val form: SshForm,
    val connecting: Boolean = false,
    val error: String? = null,
    val hostKeyPrompt: HostKeyPrompt? = null,
    /** A shell is attached (or was, see [ended]): show the terminal instead of the form. */
    val open: Boolean = false,
    /** Why the shell stopped, while its last screen stays visible. */
    val ended: String? = null,
    /** Open while the user imports a key. */
    val keyImport: KeyImport? = null,
    /** A new key is being made. */
    val creatingKey: Boolean = false,
) {
    /**
     * No shell is open and the form already has everything to sign in with. With Lock Terminal on, that's when
     * confirming it's the owner can stand in for the password: Connect asks, then signs in.
     */
    val canQuickConnect: Boolean get() = !open && form.canSubmit
}

/** Updates the server's Ubuntu/Debian packages. `&&` so the upgrade only runs once the package lists refreshed. */
const val SYSTEM_UPDATE_COMMAND = "sudo apt update && sudo apt upgrade -y"

/**
 * Holds the SSH shell and the terminal emulator. It is scoped to the signed-in account, so the shell
 * keeps running while the user switches tabs. All emulator access happens on the main thread.
 */
class TerminalViewModel(
    private val profiles: SshProfileStore,
    private val prefs: PrefsStore,
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

    /** The text picked out for copying, if any. */
    var selection by mutableStateOf<TerminalSelection?>(null)
        private set

    /** The text size in sp. Kept between sessions. */
    var fontSp by mutableFloatStateOf(prefs.terminalFontSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP))
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
            var away = false
            sessions.collect {
                if (it != owner) {
                    close(forgetSecrets = true)
                    away = true
                } else if (away) {
                    // Back on this server: show what's saved for it again. After a sign-out that's nothing,
                    // but a key that's still saved must show, or "Create a key" would replace it.
                    away = false
                    reloadSecrets()
                }
            }
        }
        // Removed on the Saved logins page: back to an empty form, as if nobody had signed in here.
        viewModelScope.launch {
            profiles.forgotten.collect { forgotten ->
                if (forgotten != server) return@collect
                close(forgetSecrets = false)
                _state.update { it.copy(form = profiles.load(server).toForm(), error = null) }
            }
        }
    }

    /** Puts the saved password and key back in the form, keeping the host, port and username as they are. */
    private fun reloadSecrets() {
        val saved = profiles.load(server).toForm()
        _state.update {
            it.copy(form = it.form.copy(password = saved.password, remember = saved.remember, key = saved.key, method = saved.method))
        }
    }

    // region form

    fun onHost(v: String) = editForm { it.copy(host = v) }
    fun onPort(v: String) = editForm { it.copy(port = v.filter(Char::isDigit).take(5)) }
    fun onUsername(v: String) = editForm { it.copy(username = v) }
    fun onPassword(v: String) = editForm { it.copy(password = v) }
    fun onRemember(v: Boolean) = editForm { it.copy(remember = v) }
    fun onMethod(v: SshMethod) = editForm { it.copy(method = v) }

    private fun editForm(f: (SshForm) -> SshForm) = _state.update { it.copy(form = f(it.form), error = null) }

    // endregion

    // region keys

    /** Makes a key on this phone. Its private half never leaves it; the user adds the public half to the server. */
    fun createKey() {
        if (_state.value.creatingKey) return
        _state.update { it.copy(creatingKey = true, error = null) }
        viewModelScope.launch {
            val key = withContext(Dispatchers.Default) { runCatching { SshKeys.generate() } }.getOrNull()
            if (key != null) useKey(key)
            _state.update { it.copy(creatingKey = false, error = if (key == null) "Couldn't make a key on this phone" else it.error) }
        }
    }

    fun openKeyImport() = _state.update { it.copy(keyImport = KeyImport(), error = null) }

    fun closeKeyImport() = _state.update { it.copy(keyImport = null) }

    /** Checks the pasted or picked key [text] and keeps it. A locked key needs its [passphrase]. */
    fun importKey(text: String, passphrase: String) {
        if (_state.value.keyImport?.busy != false) return
        _state.update { it.copy(keyImport = KeyImport(busy = true)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { SshKeys.read(text, passphrase.ifEmpty { null }) } }
            val key = result.getOrNull()
            if (key != null) {
                useKey(key)
                _state.update { it.copy(keyImport = null) }
            } else {
                val message = (result.exceptionOrNull() as? SshKeyException)?.message ?: "Couldn't read that key"
                // Still open: the user may have closed the dialog while the key was being checked.
                _state.update { if (it.keyImport != null) it.copy(keyImport = KeyImport(error = message)) else it }
            }
        }
    }

    /** Saved straight away, not at the next sign-in: the user has to go and add a new key to the server first. */
    private fun useKey(key: SshKey) {
        profiles.saveKey(server, key)
        _state.update { it.copy(form = it.form.copy(key = key, method = SshMethod.Key), error = null) }
    }

    fun removeKey() {
        profiles.saveKey(server, null)
        _state.update { it.copy(form = it.form.copy(key = null), error = null) }
    }

    // endregion

    // region connection

    fun connect() {
        val form = _state.value.form
        val port = form.portNumber ?: return
        val auth = form.auth ?: return
        if (!form.canSubmit || _state.value.connecting) return
        _state.update { it.copy(connecting = true, error = null) }
        connectJob = viewModelScope.launch {
            val opened = try {
                SshShell.open(form.host.trim(), port, form.username.trim(), auth, profiles, columns, rows)
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
                SshProfile(
                    host = form.host.trim(),
                    port = port,
                    username = form.username.trim(),
                    password = form.password.takeIf { form.remember && it.isNotEmpty() },
                    useKey = form.method == SshMethod.Key,
                ),
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
        selection = null
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

    /**
     * Keeps a scrolled-back view, and any selection, on the same lines while new output pushes the screen up.
     * With a selection the view stays put even on the live screen, so the text doesn't move under the finger.
     */
    private fun followOutput(emu: TerminalEmulator) {
        val scrolled = emu.scrollCounter
        emu.clearScrollCounter()
        if (scrolled == 0) return
        val oldest = -emu.screen.activeTranscriptRows
        val picked = selection
        if (picked != null) selection = picked.scrolledUp(scrolled, oldest)
        if (topRow != 0 || selection != null) topRow = (topRow - scrolled).coerceIn(oldest, 0)
    }

    /** Back to the sign-in form. */
    fun disconnect() = close(forgetSecrets = false)

    private fun close(forgetSecrets: Boolean) {
        connectJob?.cancel()
        ioJob?.cancel()
        val s = shell
        shell = null
        outgoing?.close()
        outgoing = null
        if (s != null) viewModelScope.launch(Dispatchers.IO) { s.close() }
        emulator = null
        selection = null
        _state.update {
            it.copy(
                connecting = false,
                open = false,
                ended = null,
                hostKeyPrompt = null,
                keyImport = null,
                form = if (forgetSecrets) it.form.copy(password = "", remember = false, key = null, method = SshMethod.Password) else it.form,
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
            selection = null // resizing rewraps the lines, so the cells no longer name the same text
            revision++
        }
        val s = shell ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { s.resize(columns, rows, columns * cellWidth, rows * cellHeight) } }
    }

    fun changeFontSize(sp: Float) {
        // Half-sp steps keep a slow pinch from resizing the remote terminal constantly.
        val next = (Math.round(sp * 2) / 2f).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        if (next == fontSp) return
        fontSp = next
        prefs.terminalFontSp = next
    }

    /** Sends typed input. Jumps back to the live screen, as terminals do on a keypress. */
    fun send(text: String) {
        if (text.isEmpty()) return
        send(text.toByteArray(Charsets.UTF_8))
    }

    private fun send(bytes: ByteArray) {
        val queue = outgoing ?: return
        queue.trySend(bytes)
        selection = null
        if (topRow != 0) topRow = 0
    }

    /** Pastes via the emulator so bracketed-paste mode is honored. */
    fun paste(text: String) {
        if (outgoing == null || text.isEmpty()) return
        emulator?.paste(text) ?: return
        selection = null
        topRow = 0
    }

    /**
     * Scrolls by [lines]; negative goes back in time. Full-screen programs (less, nano, htop) keep no history,
     * so there the scroll goes to the program instead: as wheel turns if it follows the mouse, else as arrow keys.
     * Returns false when there is nothing further to scroll to.
     */
    fun scrollBy(lines: Int): Boolean {
        val emu = emulator ?: return false
        if (lines == 0) return true
        if (outgoing != null && emu.isMouseTrackingActive) {
            val button = if (lines < 0) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
            repeat(abs(lines)) { emu.sendMouseEvent(button, columns / 2 + 1, rows / 2 + 1, true) }
            return true
        }
        if (emu.isAlternateBufferActive) {
            if (outgoing == null) return false
            val key = if (lines < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
            val code = KeyHandler.getCode(key, 0, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode) ?: return false
            send(code.repeat(abs(lines)))
            return true
        }
        return scrollHistory(lines)
    }

    /** Moves the view through the scrollback only, whatever is running. Returns false at either end. */
    fun scrollHistory(lines: Int): Boolean {
        val emu = emulator ?: return false
        val next = (topRow + lines).coerceIn(-emu.screen.activeTranscriptRows, 0)
        if (next == topRow) return false
        topRow = next
        return true
    }

    fun scrollToBottom() {
        topRow = 0
    }

    fun runCommand(command: String) = send(command + "\r")

    // endregion

    // region selection

    private fun clamp(cell: Cell): Cell? {
        val emu = emulator ?: return null
        return Cell(cell.col.coerceIn(0, emu.mColumns - 1), cell.row.coerceIn(-emu.screen.activeTranscriptRows, emu.mRows - 1))
    }

    /** Selects the word at [cell], as a long press does. Returns false if there's only blank space there. */
    fun selectWordAt(cell: Cell): Boolean {
        val emu = emulator ?: return false
        val at = clamp(cell) ?: return false
        if (at != cell) return false
        val line = emu.screen.allocateFullLineIfNecessary(emu.screen.externalToInternalRow(at.row))
        val word = wordAround(at.col, emu.mColumns) { col ->
            val c = line.mText[line.findStartOfColumn(col)]
            c == ' ' || c.code == 0
        } ?: return false
        selection = TerminalSelection(Cell(word.first, at.row), Cell(word.last, at.row))
        return true
    }

    /** Moves the selection: [anchor] is the end that stays, [focus] the one under the finger. */
    fun select(anchor: Cell, focus: Cell) {
        selection = TerminalSelection(clamp(anchor) ?: return, clamp(focus) ?: return)
    }

    /** Everything on screen and in the scrollback. */
    fun selectAll() {
        val emu = emulator ?: return
        selection = TerminalSelection(Cell(0, -emu.screen.activeTranscriptRows), Cell(emu.mColumns - 1, emu.mRows - 1))
    }

    fun clearSelection() {
        selection = null
    }

    /** The selected text as it would be typed: wrapped lines joined, trailing blanks dropped. */
    fun selectedText(): String? {
        val emu = emulator ?: return null
        val picked = selection ?: return null
        return emu.getSelectedText(picked.start.col, picked.start.row, picked.end.col, picked.end.row)
    }

    /** The whole session so far. */
    fun allText(): String {
        val emu = emulator ?: return ""
        return emu.getSelectedText(0, -emu.screen.activeTranscriptRows, emu.mColumns - 1, emu.mRows - 1)
    }

    /** The theme's terminal colors changed: [TerminalTheme.apply] has set the new defaults. */
    fun recolor() {
        emulator?.mColors?.reset()
        revision++
    }

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

    companion object {
        const val MIN_FONT_SP = 8f
        const val MAX_FONT_SP = 24f
        private const val SCROLLBACK_ROWS = 3000
    }
}

private fun SshProfile.toForm() = SshForm(
    host = host,
    port = port.toString(),
    username = username,
    password = password.orEmpty(),
    remember = password != null,
    method = if (useKey) SshMethod.Key else SshMethod.Password,
    key = key,
)
