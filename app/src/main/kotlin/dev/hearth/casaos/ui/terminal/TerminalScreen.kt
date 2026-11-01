package dev.hearth.casaos.ui.terminal

import android.content.ClipboardManager
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.KeyHandler
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.readableWidth

@Composable
fun TerminalScreen(viewModel: TerminalViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.open) SessionView(viewModel, state) else SignInForm(viewModel, state)
    state.hostKeyPrompt?.let { HostKeyDialog(it, viewModel::trustHostKey, viewModel::dismissHostKey) }
}

// region signed-in terminal

@Composable
private fun SessionView(viewModel: TerminalViewModel, state: TerminalUiState) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var confirmUpdate by remember { mutableStateOf(false) }
    val inputView = remember { TerminalInputView(context) }

    // Sticky Ctrl/Alt from the extra-keys row apply to the next key only.
    DisposableEffect(inputView, viewModel) {
        inputView.input = object : TerminalInput {
            override fun onText(text: String, ctrl: Boolean, alt: Boolean) {
                viewModel.send(TerminalKeys.encode(text, ctrl = ctrl || ctrlLatched(), alt = alt || altLatched()))
                releaseModifiers()
            }

            override fun onKey(keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): Boolean {
                val emu = viewModel.emulator ?: return false
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
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Terminal", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${state.form.username.trim()}@${state.form.host.trim()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = { confirmUpdate = true }, enabled = state.ended == null) {
                Icon(Icons.Rounded.SystemUpdateAlt, contentDescription = "Update server")
            }
            IconButton(
                onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!text.isNullOrEmpty()) viewModel.paste(text)
                },
                enabled = state.ended == null,
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste")
            }
            IconButton(onClick = viewModel::disconnect) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Disconnect")
            }
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
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp)),
        )

        ExtraKeys(
            ctrl = ctrl,
            alt = alt,
            onCtrl = { ctrl = !ctrl; haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) },
            onAlt = { alt = !alt; haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) },
            onKey = { inputView.input?.onKey(it, ctrl = false, alt = false, shift = false) },
            onText = { inputView.input?.onText(it) },
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

private sealed interface Extra {
    val label: String
    data class Key(override val label: String, val keyCode: Int, val description: String = label) : Extra
    data class Chars(override val label: String, val text: String = label) : Extra
}

private val extraKeys = listOf(
    Extra.Key("Esc", KeyEvent.KEYCODE_ESCAPE, "Escape"),
    Extra.Key("Tab", KeyEvent.KEYCODE_TAB),
    Extra.Key("←", KeyEvent.KEYCODE_DPAD_LEFT, "Left"),
    Extra.Key("↓", KeyEvent.KEYCODE_DPAD_DOWN, "Down"),
    Extra.Key("↑", KeyEvent.KEYCODE_DPAD_UP, "Up"),
    Extra.Key("→", KeyEvent.KEYCODE_DPAD_RIGHT, "Right"),
    Extra.Key("Home", KeyEvent.KEYCODE_MOVE_HOME),
    Extra.Key("End", KeyEvent.KEYCODE_MOVE_END),
    Extra.Key("PgUp", KeyEvent.KEYCODE_PAGE_UP, "Page up"),
    Extra.Key("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, "Page down"),
    Extra.Chars("|"),
    Extra.Chars("-"),
    Extra.Chars("/"),
    Extra.Chars("~"),
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
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ExtraKey("Ctrl", selected = ctrl, onClick = onCtrl)
        ExtraKey("Alt", selected = alt, onClick = onAlt)
        extraKeys.forEach { k ->
            when (k) {
                is Extra.Key -> ExtraKey(k.label, description = k.description) { onKey(k.keyCode) }
                is Extra.Chars -> ExtraKey(k.label) { onText(k.text) }
            }
        }
    }
}

@Composable
private fun ExtraKey(label: String, selected: Boolean = false, description: String = label, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.height(40.dp),
    ) {
        Box(Modifier.padding(horizontal = 14.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        }
    }
}

// endregion

// region sign-in

@Composable
private fun SignInForm(viewModel: TerminalViewModel, state: TerminalUiState) {
    val form = state.form
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).readableWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Terminal", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineMedium)
        Text(
            "Open a shell on the server over SSH to run commands or update CasaOS. Sign in with a Linux account on the server, which may differ from your CasaOS login.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HearthCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                )
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
        }

        AnimatedVisibility(state.error != null) {
            Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = viewModel::connect,
            enabled = form.canSubmit && !state.connecting,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            if (state.connecting) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Connect", style = MaterialTheme.typography.titleMedium)
            }
        }

        Text(
            "SSH encrypts the connection. Most CasaOS installs already run an SSH server. If connecting fails, install one on the server with: sudo apt install openssh-server",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

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
