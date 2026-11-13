package dev.hearth.casaos.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearth.casaos.data.SavedLogin
import dev.hearth.casaos.data.SavedSshLogin
import dev.hearth.casaos.data.SshProfileStore
import dev.hearth.casaos.data.WebLogins
import dev.hearth.casaos.ui.components.AppIcon
import dev.hearth.casaos.ui.components.BackButton
import dev.hearth.casaos.ui.components.CardPadding
import dev.hearth.casaos.ui.components.GroupFootnote
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.ListGroup
import dev.hearth.casaos.ui.components.ListRow
import dev.hearth.casaos.ui.components.SectionHeader
import dev.hearth.casaos.ui.components.SwitchRow
import dev.hearth.casaos.ui.components.hearthTopBarColors
import dev.hearth.casaos.ui.components.readableWidth

/**
 * Settings › Saved logins: whether apps opened in Hearth stay signed in, and which ones are, each with a
 * way to sign out of it. Like a browser's password list, except Hearth keeps the app's login (its cookies)
 * rather than the password. Below the apps, the SSH login the Terminal tab saved for each of [servers]
 * (the hosts of the CasaOS servers that are signed in), which [ssh] keeps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedLoginsScreen(logins: WebLogins, ssh: SshProfileStore, servers: List<String>, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val keep by logins.remember.collectAsStateWithLifecycle()
    val perApp = remember(logins) { logins.perApp }
    var saved by remember { mutableStateOf<List<SavedLogin>?>(null) }
    // Bumped to read the lists again: after a removal, and on returning to the page.
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { saved = logins.saved() }
    val terminal = remember(reload, servers) { ssh.saved(servers) }
    LifecycleResumeEffect(Unit) {
        reload++
        onPauseOrDispose {}
    }

    var removing by remember { mutableStateOf<SavedLogin?>(null) }
    var removingTerminal by remember { mutableStateOf<SavedSshLogin?>(null) }
    var removingAll by remember { mutableStateOf(false) }
    var turningOff by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onClose) },
                title = { Text("Saved logins") },
                colors = hearthTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readableWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ListGroup {
                SwitchRow(
                    "Remember logins",
                    checked = keep,
                    onCheckedChange = { on -> if (on) logins.setRemember(true) else turningOff = true },
                    subtitle = if (keep) "Apps stay signed in" else "Apps sign you out when you close them",
                    icon = Icons.Rounded.Key,
                )
            }
            GroupFootnote(
                "Hearth keeps each app's login, not its password. Signing out of Hearth signs you out of every app too.",
            )

            SectionHeader("Apps")
            val list = saved
            when {
                !perApp -> Notice(
                    "This phone's WebView keeps one set of logins for every app, so they can only be removed together. " +
                        "Updating Android System WebView from the Play Store lets Hearth list them per app.",
                )
                list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
                list.isEmpty() -> Notice(
                    if (keep) "No saved logins. Apps you sign in to inside Hearth show up here." else "No saved logins.",
                )
                else -> ListGroup {
                    list.forEach { login ->
                        ListRow(
                            login.name,
                            subtitle = login.origin.substringAfter("://"),
                            leading = { AppIcon(login.iconUrl, login.name, size = 40.dp) },
                            trailing = {
                                IconButton(onClick = { removing = login }) { Icon(Icons.Rounded.Close, "Remove ${login.name}'s login") }
                            },
                        )
                    }
                }
            }

            if (!perApp || !list.isNullOrEmpty()) {
                ListGroup(Modifier.padding(top = 8.dp)) {
                    ListRow(
                        "Remove all app logins",
                        icon = Icons.Rounded.DeleteSweep,
                        iconTint = MaterialTheme.colorScheme.error,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { removingAll = true },
                    )
                }
            }

            SectionHeader("Terminal")
            if (terminal.isEmpty()) {
                Notice("Nothing saved. The SSH login you use on the Terminal tab shows up here.")
            } else {
                ListGroup {
                    terminal.forEach { login ->
                        ListRow(
                            login.address,
                            subtitle = when {
                                login.hasPassword && login.hasKey -> "Password and SSH key saved"
                                login.hasKey -> "SSH key saved"
                                login.hasPassword -> "Password saved"
                                else -> "No password saved"
                            },
                            icon = Icons.Rounded.Terminal,
                            trailing = {
                                IconButton(onClick = { removingTerminal = login }) {
                                    Icon(Icons.Rounded.Close, "Remove the terminal login ${login.address}")
                                }
                            },
                        )
                    }
                }
                GroupFootnote("A saved password or key is kept encrypted on this phone, and goes when you sign out of Hearth.")
            }
        }
    }

    removingTerminal?.let { login ->
        Confirm(
            title = "Remove this terminal login?",
            text = "Hearth forgets ${login.address}" +
                (if (login.hasPassword) " and its password" else "") +
                ", and closes the terminal if it's connected. Pages that read the server over SSH, such as the " +
                "Task manager, need you to sign in on the Terminal tab again." +
                if (login.hasKey) "\n\nThe SSH key can't be brought back, so a server that only knows this key will need a new one added." else "",
            action = "Remove",
            onConfirm = { ssh.forget(login.server); reload++ },
            onDismiss = { removingTerminal = null },
        )
    }

    removing?.let { login ->
        Confirm(
            title = "Remove ${login.name}'s login?",
            text = "You'll need to sign in to ${login.name} again next time you open it.",
            action = "Remove",
            onConfirm = { logins.forget(login.origin); reload++ },
            onDismiss = { removing = null },
        )
    }
    if (removingAll) {
        Confirm(
            title = "Remove all app logins?",
            text = "You'll need to sign in again to every app you open in Hearth.",
            action = "Remove all",
            onConfirm = { logins.forgetAll(); reload++ },
            onDismiss = { removingAll = false },
        )
    }
    if (turningOff) {
        Confirm(
            title = "Stop remembering logins?",
            text = "You'll be signed out of every app now, and of each app whenever you close it.",
            action = "Turn off",
            onConfirm = { logins.setRemember(false); reload++ },
            onDismiss = { turningOff = false },
        )
    }
}

@Composable
private fun Notice(text: String) {
    HearthCard(Modifier.fillMaxWidth()) {
        Text(
            text,
            Modifier.padding(CardPadding),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Confirm(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(action, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
