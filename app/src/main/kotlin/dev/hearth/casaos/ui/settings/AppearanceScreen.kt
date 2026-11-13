package dev.hearth.casaos.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearth.casaos.ui.components.BackButton
import dev.hearth.casaos.ui.components.CardPadding
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.SectionHeader
import dev.hearth.casaos.ui.components.hearthTopBarColors
import dev.hearth.casaos.ui.components.readableWidth

/** Settings › Appearance: light or dark, the accent color, the Android or One UI style, and what's on Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(viewModel: SettingsViewModel, onClose: () -> Unit, onCustomizeHome: () -> Unit, modifier: Modifier = Modifier) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val homeWidgets by viewModel.homeWidgets.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

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
            SectionHeader("Your name")
            HearthCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = viewModel::setNickname,
                    modifier = Modifier.fillMaxWidth().padding(CardPadding),
                    label = { Text("Nickname") },
                    supportingText = { Text("Used in your Home greeting. Leave blank to use your CasaOS username.") },
                    leadingIcon = { Icon(Icons.Rounded.PersonOutline, null) },
                    trailingIcon = {
                        if (nickname.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setNickname("") }) {
                                Icon(Icons.Rounded.Close, "Clear nickname")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
            }
            AppearanceSection(theme, viewModel::setTheme)
            CustomizeHomeRow(homeWidgets, onCustomizeHome)
        }
    }
}
