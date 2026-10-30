package dev.hearth.casaos.ui.connect

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hearth.casaos.R
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.ServerProfile
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.SectionLabel
import dev.hearth.casaos.ui.servers.ServerRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectState(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** Set once a sign-in worked, so the "Add a server" page can close. */
    val signedIn: Boolean = false,
) {
    val canSubmit get() = server.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && !loading
}

/** [prefill] is a saved server to sign in to again; its address and username start filled in. */
class ConnectViewModel(private val client: CasaClient, prefill: ServerProfile?) : ViewModel() {
    private val _state = MutableStateFlow(ConnectState(server = prefill?.address.orEmpty(), username = prefill?.username.orEmpty()))
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    /** Fills the form in for a saved server that's signed out. */
    fun prefill(profile: ServerProfile) =
        _state.update { it.copy(server = profile.address, username = profile.username, password = "", error = null) }

    fun onServer(v: String) = _state.update { it.copy(server = v, error = null) }
    fun onUsername(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                client.login(s.server, s.username.trim(), s.password)
                // On success SessionStore flips and the root composable swaps this screen out.
                _state.update { it.copy(loading = false, password = "", signedIn = true) }
            } catch (e: CasaException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}

/**
 * Signs in to a server. With [onClose] it's the "Add a server" page, opened over a signed-in app; without,
 * it's the first screen, listing [saved] servers (other than the one in the form) to switch to.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ConnectScreen(
    viewModel: ConnectViewModel,
    saved: List<ServerProfile> = emptyList(),
    onSwitch: (ServerProfile) -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val adding = onClose != null
    if (adding) {
        BackHandler(onBack = onClose)
        LaunchedEffect(state.signedIn) { if (state.signedIn) onClose() }
    }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    val ember = MaterialTheme.colorScheme.primary

    fun submit() {
        keyboard?.hide()
        focus.clearFocus()
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        viewModel.submit()
    }

    // A Surface (not a bare background) so untinted text gets onBackground instead of defaulting to black.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) {
        // Soft ember glow behind the hero.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(ember.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 0.12f),
                    radius = size.width * 0.95f,
                ),
                radius = size.width * 0.95f,
                center = Offset(size.width / 2, size.height * 0.12f),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onClose != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }
            }
            Spacer(Modifier.height(if (adding) 16.dp else 72.dp))
            Box(
                Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(84.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(if (adding) "Add a server" else "Hearth", style = MaterialTheme.typography.displaySmall)
            Text(
                if (adding) "Sign in to another CasaOS server." else "Your home server, in your pocket.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = state.server,
                    onValueChange = viewModel::onServer,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server address") },
                    placeholder = { Text("192.168.1.10 or casaos.local") },
                    leadingIcon = { Icon(Icons.Rounded.Dns, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsername,
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                    label = { Text("Username") },
                    leadingIcon = { Icon(Icons.Rounded.Person, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPassword,
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
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
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
            }

            AnimatedVisibility(state.error != null) {
                Text(
                    state.error.orEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = ::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Connect", style = MaterialTheme.typography.titleMedium)
                }
            }

            // Other saved servers: signed-in ones open right away, signed-out ones fill the form in.
            val others = saved.filterNot { it.address.equals(state.server.trim(), ignoreCase = true) && it.username.equals(state.username.trim(), ignoreCase = true) }
            if (!adding && others.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                SectionLabel("Your servers", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                HearthCard(Modifier.fillMaxWidth()) {
                    others.forEachIndexed { i, profile ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ServerRow(profile, selected = false, onClick = { if (profile.signedIn) onSwitch(profile) else viewModel.prefill(profile) })
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Use the same login as your CasaOS web dashboard. Over plain HTTP your password travels unencrypted, so keep to your home network or a VPN.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    } }
}
