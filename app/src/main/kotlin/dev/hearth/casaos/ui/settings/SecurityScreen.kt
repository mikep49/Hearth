package dev.hearth.casaos.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearth.casaos.ui.components.BackButton
import dev.hearth.casaos.ui.components.GroupFootnote
import dev.hearth.casaos.ui.components.ListGroup
import dev.hearth.casaos.ui.components.SectionHeader
import dev.hearth.casaos.ui.components.SwitchRow
import dev.hearth.casaos.ui.components.hearthTopBarColors
import dev.hearth.casaos.ui.components.readableWidth

/** Settings › Security & privacy: the locks, and how careful the in-app browser is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(viewModel: SettingsViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val lockEnabled by viewModel.lockEnabled.collectAsStateWithLifecycle()
    val lockAfter by viewModel.lockAfter.collectAsStateWithLifecycle()
    val showInFiles by viewModel.showInFiles.collectAsStateWithLifecycle()
    val terminalLocked by viewModel.terminalLocked.collectAsStateWithLifecycle()
    val appsLocked by viewModel.appsLocked.collectAsStateWithLifecycle()
    val strictBrowser by viewModel.strictBrowser.collectAsStateWithLifecycle()
    var confirmStrict by rememberSaveable { mutableStateOf(false) }

    // This page covers the Settings tab and its snackbar, so it shows messages itself.
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    BackHandler(onBack = onClose)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onClose) },
                title = { Text("Security & privacy") },
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
            SectionHeader("Lock")
            SecuritySection(
                lockEnabled,
                lockAfter,
                showInFiles = showInFiles,
                available = viewModel::lockAvailable,
                onEnabledChange = viewModel::setLockEnabled,
                onLockAfterChange = viewModel::setLockAfter,
                terminalLocked = terminalLocked,
                onTerminalLockedChange = viewModel::setTerminalLocked,
                appsLocked = appsLocked,
                onAppsLockedChange = viewModel::setAppsLocked,
                onError = viewModel::showError,
            )

            SectionHeader("Browsing")
            ListGroup {
                SwitchRow(
                    "Strict browsing",
                    checked = strictBrowser,
                    // Turning it on can break an app, so that asks first. Turning it off never does.
                    onCheckedChange = { on -> if (on) confirmStrict = true else viewModel.setStrictBrowser(false) },
                    subtitle = "Blocks cross-site cookies and insecure content",
                    icon = Icons.Rounded.Shield,
                )
            }
            GroupFootnote("For apps opened in Hearth. Off by default.")
        }
    }

    if (confirmStrict) {
        AlertDialog(
            onDismissRequest = { confirmStrict = false },
            title = { Text("Turn on strict browsing?") },
            text = {
                Text(
                    "Pages opened over https will no longer load anything over plain http, and cookies from other " +
                        "sites are blocked.\n\n" +
                        "Apps you open over http still work. If an app stops loading properly, turn this off.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setStrictBrowser(true); confirmStrict = false }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { confirmStrict = false }) { Text("Cancel") } },
        )
    }
}
