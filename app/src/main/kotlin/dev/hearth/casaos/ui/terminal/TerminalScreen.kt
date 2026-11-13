package dev.hearth.casaos.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.East
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.West
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.KeyHandler
import dev.hearth.casaos.data.SshKey
import dev.hearth.casaos.data.SshKeys
import dev.hearth.casaos.ui.components.CardPadding
import dev.hearth.casaos.ui.components.HeaderSubtitle
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.IconTile
import dev.hearth.casaos.ui.components.ScreenHeader
import dev.hearth.casaos.ui.components.StatusDot
import dev.hearth.casaos.ui.components.readableWidth
import dev.hearth.casaos.ui.theme.hearth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TerminalScreen(viewModel: TerminalViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.open) SessionView(viewModel, state) else SignInForm(viewModel, state)
    state.hostKeyPrompt?.let { HostKeyDialog(it, viewModel::trustHostKey, viewModel::dismissHostKey) }
}

// region signed-in terminal

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionView(viewModel: TerminalViewModel, state: TerminalUiState) {
    val context = LocalContext.current
    val view = LocalView.current
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var confirmUpdate by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val inputView = remember { TerminalInputView(context) }
    val live = state.ended == null
    val paste = { readClipboard(context)?.let(viewModel::paste) ?: Unit }
    val copy = { text: String -> copyToClipboard(context, text) }

    // Sticky Ctrl/Alt from the extra keys apply to the next key only.
    DisposableEffect(inputView, viewModel) {
        inputView.input = object : TerminalInput {
            override fun onText(text: String, ctrl: Boolean, alt: Boolean) {
                viewModel.send(TerminalKeys.encode(text, ctrl = ctrl || ctrlLatched(), alt = alt || altLatched()))
                releaseModifiers()
            }

            override fun onKey(keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): Boolean {
                val emu = viewModel.emulator ?: return false
                // Ctrl+Shift+C and V, as in desktop terminals, where plain Ctrl+C is for the program.
                if (ctrl && shift && keyCode == KeyEvent.KEYCODE_C) {
                    viewModel.selectedText()?.let(copy)
                    viewModel.clearSelection()
                    return true
                }
                if (ctrl && shift && keyCode == KeyEvent.KEYCODE_V) {
                    paste()
                    return true
                }
                val useCtrl = ctrl || ctrlLatched()
                val useAlt = alt || altLatched()
                val code = when (keyCode) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> (if (useAlt) TerminalKeys.ESC else "") + "\r"
                    KeyEvent.KEYCODE_DEL -> (if (useAlt) TerminalKeys.ESC else "") + (if (useCtrl) "\b" else TerminalKeys.DEL)
                    else -> {
                        var mods = 0
                        if (useCtrl) mods = mods or KeyHandler.KEYMOD_CTRL
                        if (useAlt) mods = mods or KeyHandler.KEYMOD_ALT
                        if (shift) mods = mods or KeyHandler.KEYMOD_SHIFT
                        KeyHandler.getCode(keyCode, mods, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode)
                    }
                } ?: return false
                viewModel.send(code)
                releaseModifiers()
                return true
            }

            private fun ctrlLatched() = ctrl
            private fun altLatched() = alt
            private fun releaseModifiers() {
                ctrl = false
                alt = false
            }
        }
        onDispose { inputView.input = null }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        val address = "${state.form.username.trim()}@${state.form.host.trim()}"
        val dot = if (live) MaterialTheme.hearth.good else MaterialTheme.hearth.bad
        val actions: @Composable RowScope.() -> Unit = {
            IconButton(onClick = paste, enabled = live) { Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste") }
            SessionMenu(
                open = menuOpen,
                onOpenChange = { menuOpen = it },
                live = live,
                onCopyAll = { copy(viewModel.allText()) },
                fontSp = viewModel.fontSp,
                onFontSize = viewModel::changeFontSize,
                onUpdate = { confirmUpdate = true },
                onDisconnect = viewModel::disconnect,
            )
        }
        // While typing, every row counts: the title shrinks to one line. It keeps its form while the menu is
        // open, since the menu takes the keyboard's focus and the bar would otherwise jump under it.
        val typing = WindowInsets.isImeVisible
        var slim by remember { mutableStateOf(typing) }
        LaunchedEffect(typing, menuOpen) { if (!menuOpen) slim = typing }
        if (slim) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dot)
                Text(
                    address,
                    Modifier.weight(1f).padding(start = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                actions()
            }
        } else {
            ScreenHeader(
                "Terminal",
                Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp),
                subtitle = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(dot)
                        HeaderSubtitle(address)
                    }
                },
                actions = actions,
            )
        }

        AnimatedVisibility(state.ended != null) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.error ?: state.ended.orEmpty(),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (state.connecting) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = viewModel::connect, enabled = state.form.canSubmit) { Text("Reconnect") }
                    }
                }
            }
        }

        TerminalPane(
            viewModel,
            inputView,
            onPaste = paste.takeIf { live },
            onCopy = copy,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )

        ExtraKeys(
            ctrl = ctrl,
            alt = alt,
            onCtrl = { ctrl = !ctrl },
            onAlt = { alt = !alt },
            onKey = { inputView.input?.onKey(it, ctrl = false, alt = false, shift = false) },
            onText = { inputView.input?.onText(it) },
            onPress = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) },
        )
    }

    if (confirmUpdate) {
        AlertDialog(
            onDismissRequest = { confirmUpdate = false },
            title = { Text("Update the server?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Installs all available Ubuntu package updates on the server. sudo may ask for this account's password: type it in the terminal.")
                    Text(SYSTEM_UPDATE_COMMAND, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("Some updates (like a new kernel) only take effect after a restart, which you can do from Settings.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUpdate = false
                    viewModel.runCommand(SYSTEM_UPDATE_COMMAND)
                    // sudo will ask for a password. Wait for the dialog's window to go, or it keeps the focus.
                    inputView.postDelayed({ inputView.showKeyboard() }, 250)
                }) { Text("Run update") }
            },
            dismissButton = { TextButton(onClick = { confirmUpdate = false }) { Text("Cancel") } },
        )
    }
}

/** The ⋮ menu: what's used now and then, with names, so the bar keeps only Paste. */
@Composable
private fun SessionMenu(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    live: Boolean,
    onCopyAll: () -> Unit,
    fontSp: Float,
    onFontSize: (Float) -> Unit,
    onUpdate: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Box {
        IconButton(onClick = { onOpenChange(true) }) { Icon(Icons.Rounded.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            DropdownMenuItem(
                text = { Text("Copy all output") },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                onClick = { onOpenChange(false); onCopyAll() },
            )
            // These two leave the menu open, to step the size up or down a few times.
            DropdownMenuItem(
                text = { Text("Bigger text") },
                leadingIcon = { Icon(Icons.Rounded.TextIncrease, null) },
                enabled = fontSp < TerminalViewModel.MAX_FONT_SP,
                onClick = { onFontSize(fontSp + 1f) },
            )
            DropdownMenuItem(
                text = { Text("Smaller text") },
                leadingIcon = { Icon(Icons.Rounded.TextDecrease, null) },
                enabled = fontSp > TerminalViewModel.MIN_FONT_SP,
                onClick = { onFontSize(fontSp - 1f) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Update server") },
                leadingIcon = { Icon(Icons.Rounded.SystemUpdateAlt, null) },
                enabled = live,
                onClick = { onOpenChange(false); onUpdate() },
            )
            DropdownMenuItem(
                text = { Text("Disconnect") },
                leadingIcon = { Icon(Icons.Rounded.PowerSettingsNew, null) },
                onClick = { onOpenChange(false); onDisconnect() },
            )
        }
    }
}

private fun readClipboard(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
    return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
}

private fun copyToClipboard(context: Context, text: String, label: String = "Terminal") {
    val trimmed = trimForClipboard(text)
    if (trimmed.isEmpty()) return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, trimmed))
    // Android 13 and later confirm a copy on their own.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private sealed interface Extra {
    /** A key the terminal encodes itself. [repeats] while held, like a real keyboard's. */
    data class Key(val label: String, val keyCode: Int, val description: String = label, val icon: ImageVector? = null, val repeats: Boolean = false) : Extra
    data class Chars(val text: String) : Extra
    data object Ctrl : Extra
    data object Alt : Extra
}

// Two rows, with the arrows in the shape they have on a keyboard. On a wide screen they sit side by side.
private val extraKeyRows = listOf(
    listOf(
        Extra.Key("Esc", KeyEvent.KEYCODE_ESCAPE, "Escape"),
        Extra.Chars("/"),
        Extra.Chars("-"),
        Extra.Chars("|"),
        Extra.Key("Home", KeyEvent.KEYCODE_MOVE_HOME),
        Extra.Key("↑", KeyEvent.KEYCODE_DPAD_UP, "Up", Icons.Rounded.North, repeats = true),
        Extra.Key("End", KeyEvent.KEYCODE_MOVE_END),
        Extra.Key("PgUp", KeyEvent.KEYCODE_PAGE_UP, "Page up", repeats = true),
    ),
    listOf(
        Extra.Key("Tab", KeyEvent.KEYCODE_TAB),
        Extra.Ctrl,
        Extra.Alt,
        Extra.Chars("~"),
        Extra.Key("←", KeyEvent.KEYCODE_DPAD_LEFT, "Left", Icons.Rounded.West, repeats = true),
        Extra.Key("↓", KeyEvent.KEYCODE_DPAD_DOWN, "Down", Icons.Rounded.South, repeats = true),
        Extra.Key("→", KeyEvent.KEYCODE_DPAD_RIGHT, "Right", Icons.Rounded.East, repeats = true),
        Extra.Key("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, "Page down", repeats = true),
    ),
)

/** Keys phone keyboards lack. Ctrl and Alt latch for the next key, like Termux. */
@Composable
private fun ExtraKeys(
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (Int) -> Unit,
    onText: (String) -> Unit,
    onPress: () -> Unit,
) {
    @Composable
    fun RowScope.Keys(keys: List<Extra>) = keys.forEach { k ->
        val key = Modifier.weight(1f)
        when (k) {
            is Extra.Key -> ExtraKey(k.label, key, description = k.description, icon = k.icon, repeats = k.repeats) { onPress(); onKey(k.keyCode) }
            is Extra.Chars -> ExtraKey(k.text, key) { onPress(); onText(k.text) }
            Extra.Ctrl -> ExtraKey("Ctrl", key, selected = ctrl, description = "Control") { onPress(); onCtrl() }
            Extra.Alt -> ExtraKey("Alt", key, selected = alt) { onPress(); onAlt() }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 6.dp)) {
        val gap = Arrangement.spacedBy(4.dp)
        if (maxWidth >= 640.dp) {
            Row(horizontalArrangement = gap) { extraKeyRows.forEach { Keys(it) } }
        } else {
            Column(verticalArrangement = gap) {
                extraKeyRows.forEach { row -> Row(horizontalArrangement = gap) { Keys(row) } }
            }
        }
    }
}

@Composable
private fun ExtraKey(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    description: String = label,
    icon: ImageVector? = null,
    repeats: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val press by rememberUpdatedState(onClick)
    // Set once a held key has repeated, so letting go doesn't count as one more press.
    var repeated by remember { mutableStateOf(false) }
    if (repeats) {
        val pressed by interaction.collectIsPressedAsState()
        LaunchedEffect(pressed) {
            if (!pressed) return@LaunchedEffect
            delay(400)
            while (true) {
                repeated = true
                press()
                delay(60)
            }
        }
    }
    Surface(
        onClick = { if (repeated) repeated = false else onClick() },
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.height(38.dp),
        interactionSource = interaction,
    ) {
        Box(Modifier.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp))
            } else {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
            }
        }
    }
}

// endregion

// region sign-in

@Composable
private fun SignInForm(viewModel: TerminalViewModel, state: TerminalUiState) {
    val form = state.form
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var confirmRemoveKey by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).readableWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Terminal", subtitle = { HeaderSubtitle("Sign in over SSH") })
        Text(
            "Use a Linux account on the server. It may differ from your CasaOS login.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HearthCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = form.host,
                        onValueChange = viewModel::onHost,
                        modifier = Modifier.weight(1f),
                        label = { Text("Host") },
                        leadingIcon = { Icon(Icons.Rounded.Dns, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        value = form.port,
                        onValueChange = viewModel::onPort,
                        modifier = Modifier.width(92.dp),
                        label = { Text("Port") },
                        singleLine = true,
                        isError = form.portNumber == null,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    )
                }
                OutlinedTextField(
                    value = form.username,
                    onValueChange = viewModel::onUsername,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SSH username") },
                    leadingIcon = { Icon(Icons.Rounded.Person, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = if (form.method == SshMethod.Password) ImeAction.Next else ImeAction.Done,
                    ),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SshMethod.entries.forEachIndexed { i, method ->
                        SegmentedButton(
                            selected = form.method == method,
                            onClick = { viewModel.onMethod(method) },
                            shape = SegmentedButtonDefaults.itemShape(i, SshMethod.entries.size),
                        ) { Text(method.label) }
                    }
                }
                when (form.method) {
                    SshMethod.Password -> {
                        OutlinedTextField(
                            value = form.password,
                            onValueChange = viewModel::onPassword,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Password") },
                            leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (showPassword) "Hide password" else "Show password",
                                    )
                                }
                            },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { viewModel.connect() }),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = form.remember, onCheckedChange = viewModel::onRemember)
                            Text("Remember password on this phone", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    SshMethod.Key -> {
                        val key = form.key
                        if (key == null) {
                            NoKey(state.creatingKey, viewModel::createKey, viewModel::openKeyImport)
                        } else {
                            SavedKey(
                                key,
                                onCopy = { copyToClipboard(context, key.publicKey, label = "SSH public key") },
                                onRemove = { confirmRemoveKey = true },
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(state.error != null) {
            Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = viewModel::connect,
            enabled = form.canSubmit && !state.connecting,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (state.connecting) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Connect", style = MaterialTheme.typography.titleMedium)
            }
        }

        Text(
            "If connecting fails, the server may need an SSH server: sudo apt install openssh-server",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    state.keyImport?.let { KeyImportDialog(it, viewModel::importKey, viewModel::closeKeyImport) }

    if (confirmRemoveKey) {
        AlertDialog(
            onDismissRequest = { confirmRemoveKey = false },
            title = { Text("Remove this key?") },
            text = { Text("Hearth forgets the key. It can't be brought back, so a server that only knows this key will need a new one added.") },
            confirmButton = {
                TextButton(onClick = { confirmRemoveKey = false; viewModel.removeKey() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveKey = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The Terminal tab while Lock Terminal has it locked and a login is ready to use (see [TerminalUiState.canQuickConnect]).
 * Connect asks the phone to confirm it's the owner, in place of the password, and signs in. The form, with the
 * password and the key in it, stays out of reach until the phone has confirmed. [error] and [unlock] are the
 * lock's: see [dev.hearth.casaos.ui.lock.LockedPage].
 */
@Composable
fun LockedSignIn(viewModel: TerminalViewModel, error: String?, unlock: (then: () -> Unit) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form
    val usesKey = form.method == SshMethod.Key

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).readableWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Terminal", subtitle = { HeaderSubtitle("Sign in over SSH") })

        HearthCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(CardPadding), verticalAlignment = Alignment.CenterVertically) {
                IconTile(if (usesKey) Icons.Rounded.Key else Icons.Rounded.Lock)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        "${form.username.trim()}@${form.host.trim()}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (if (usesKey) "SSH key" else "Password") + if (form.portNumber == 22) "" else ", port ${form.port.trim()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        AnimatedVisibility(error != null) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = { unlock(viewModel::connect) },
            enabled = !state.connecting,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (state.connecting) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Rounded.Fingerprint, null, Modifier.size(22.dp))
                Text("Connect", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        TextButton(onClick = { unlock {} }, Modifier.align(Alignment.CenterHorizontally)) { Text("Change sign-in details") }

        Text(
            "Lock Terminal is on, so Hearth asks for your fingerprint, face or PIN in place of the " +
                (if (usesKey) "key" else "password") + ".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoKey(creating: Boolean, onCreate: () -> Unit, onImport: () -> Unit) {
    Text(
        "A key signs you in without a password, and works on servers that don't take passwords. " +
            "Make a new one on this phone, or bring in one you already use.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onCreate, enabled = !creating, modifier = Modifier.weight(1f)) {
            if (creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Create a key")
        }
        OutlinedButton(onClick = onImport, enabled = !creating, modifier = Modifier.weight(1f)) { Text("Import a key") }
    }
}

@Composable
private fun SavedKey(key: SshKey, onCopy: () -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(Icons.Rounded.Key)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(key.type, style = MaterialTheme.typography.titleSmall)
            Text(
                key.fingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.DeleteOutline, "Remove key") }
    }
    Text(
        "The server has to know this key. Copy its public key and add it as a line of ~/.ssh/authorized_keys " +
            "on the server, in the home folder of the user above.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FilledTonalButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
        Text("Copy public key", Modifier.padding(start = 8.dp))
    }
}

/** Takes a private key as pasted text or a picked file, with the passphrase if it has one. */
@Composable
private fun KeyImportDialog(import: KeyImport, onImport: (text: String, passphrase: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Not saved with the screen's state: a private key shouldn't be written anywhere unencrypted.
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var fileError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) { readKeyFile(context, uri) }
            if (read == null) fileError = "That file isn't a key: it's too big or can't be read." else { text = read; fileError = null }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste a private key or choose its file, such as id_ed25519. Hearth keeps it encrypted on this phone.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; fileError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Private key") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 3,
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                )
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose a file") }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Passphrase, if it has one") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                )
                val error = fileError ?: import.error
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text, passphrase) }, enabled = text.isNotBlank() && !import.busy) {
                if (import.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Import")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The file's text, or null if it can't be read or is too big to be a key. */
private fun readKeyFile(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val reader = stream.reader()
        val buffer = CharArray(SshKeys.MAX_KEY_CHARS + 1)
        var size = 0
        while (size < buffer.size) {
            val n = reader.read(buffer, size, buffer.size - size)
            if (n < 0) break
            size += n
        }
        if (size > SshKeys.MAX_KEY_CHARS) null else String(buffer, 0, size)
    }
}.getOrNull()

// endregion

@Composable
private fun HostKeyDialog(prompt: HostKeyPrompt, onTrust: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (prompt.changed) "Server identity changed" else "Verify this server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (prompt.changed) {
                        "The key ${prompt.key.id} presented doesn't match the one you trusted before. That's expected if the server was reinstalled; otherwise someone may be intercepting the connection. Don't continue unless you know why it changed."
                    } else {
                        "First connection to ${prompt.key.id}. To be sure it's your server, compare this fingerprint with what the server prints for ssh-keygen -lf on its host key."
                    },
                )
                Text(prompt.key.type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(prompt.key.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(
                    if (prompt.changed) "Trust new key" else "Trust and connect",
                    color = if (prompt.changed) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
