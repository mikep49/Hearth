package dev.hearth.casaos.ui.lock

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.hearth.casaos.data.AppLock
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first

/**
 * Asks the phone to confirm it's the owner: fingerprint or face, or the phone's PIN, pattern or password.
 * [onError] gets a message to show, or null when the person just cancelled.
 */
fun FragmentActivity.confirmOwner(title: String, subtitle: String?, onSuccess: () -> Unit, onError: (String?) -> Unit = {}) {
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                errorCode == BiometricPrompt.ERROR_CANCELED ||
                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
            onError(if (cancelled) null else errString.toString())
        }
    }
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply { if (subtitle != null) setSubtitle(subtitle) }
        // Weak biometrics plus the screen lock works on every Android Hearth supports; it guards the app, not a key.
        .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        .build()
    BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback).authenticate(info)
}

/** Draws [content], covered by a lock screen while [lock] is locked. The content stays alive underneath. */
@Composable
fun LockGate(lock: AppLock, content: @Composable () -> Unit) {
    val locked by lock.locked.collectAsStateWithLifecycle()
    val enabled by lock.enabled.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    // Keeps Hearth's screens out of the Recents thumbnail while the lock is on.
    LaunchedEffect(enabled, activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity?.setRecentsScreenshotEnabled(!enabled)
    }

    Box(Modifier.fillMaxSize()) {
        // Hidden from screen readers too while locked.
        Box(Modifier.fillMaxSize().then(if (locked) Modifier.clearAndSetSemantics {} else Modifier)) { content() }
        if (locked) LockScreen(lock)
    }
}

@Composable
private fun LockScreen(lock: AppLock) {
    val activity = LocalActivity.current as? FragmentActivity
    val owner = LocalLifecycleOwner.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var error by remember { mutableStateOf<String?>(null) }

    fun ask() {
        val a = activity ?: return
        error = null
        a.confirmOwner("Unlock Hearth", null, onSuccess = lock::unlock, onError = { error = it })
    }

    // Takes focus and the keyboard from whatever was being typed into (the terminal, a form).
    LaunchedEffect(Unit) {
        keyboard?.hide()
        runCatching { focus.requestFocus() }
    }
    // Asks once each time Hearth comes to the front locked. After a cancel, the button asks again.
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            owner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            ask()
            awaitCancellation()
        }
    }
    BackHandler { activity?.moveTaskToBack(true) }

    // A Surface takes every touch, so nothing underneath can be tapped.
    Surface(Modifier.fillMaxSize().focusRequester(focus).focusable(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text("Hearth is locked", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Unlock with your fingerprint, face or phone PIN.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Button(onClick = ::ask, Modifier.padding(top = 8.dp)) {
                Icon(Icons.Rounded.Fingerprint, null, Modifier.size(ButtonDefaults.IconSize))
                Text("Unlock", Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}
