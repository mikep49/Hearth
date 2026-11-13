package dev.hearth.casaos.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearth.casaos.ui.components.BackButton
import dev.hearth.casaos.ui.components.hearthTopBarColors
import dev.hearth.casaos.ui.components.readableWidth

/** Settings › Appearance: light or dark, the accent color, the Android or One UI style, and what's on Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(viewModel: SettingsViewModel, onClose: () -> Unit, onCustomizeHome: () -> Unit, modifier: Modifier = Modifier) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val homeWidgets by viewModel.homeWidgets.collectAsStateWithLifecycle()

    BackHandler(onBack = onClose)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onClose) },
                title = { Text("Appearance") },
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
            AppearanceSection(theme, viewModel::setTheme)
            CustomizeHomeRow(homeWidgets, onCustomizeHome)
        }
    }
}
