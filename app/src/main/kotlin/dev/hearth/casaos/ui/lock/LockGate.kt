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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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

/**
 * How many parts with a lock of their own that's turned on are on screen, counted by each [PartLockGate] for the
 * [LockGate] around it. Only [LockGate] tells Android whether to keep the screen out of Recents, so the two
 * can't undo each other.
 */
private val LocalGuardedPartsShown = compositionLocalOf<MutableIntState> { error("PartLockGate must be inside LockGate") }

/** Draws [content], covered by a lock screen while [lock] is locked. The content stays alive underneath. */
@Composable
fun LockGate(lock: AppLock, content: @Composable () -> Unit) {
    val locked by lock.locked.collectAsStateWithLifecycle()
    val enabled by lock.enabled.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val guardedPartsShown = remember { mutableIntStateOf(0) }

    // Keeps Hearth's screens out of the Recents thumbnail while the lock is on, or while what's on screen is a
    // part with a lock of its own (Android 13+). Read when this runs, not when it was composed: a part that
    // came on screen in the same pass (after rotating, or an app opened from its icon) has counted itself by then.
    LaunchedEffect(activity) {
        snapshotFlow { enabled || guardedPartsShown.intValue > 0 }.collect { hide ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity?.setRecentsScreenshotEnabled(!hide)
        }
    }

    CompositionLocalProvider(LocalGuardedPartsShown provides guardedPartsShown) {
        Box(Modifier.fillMaxSize()) {
            // Hidden from screen readers too while locked.
            Box(Modifier.fillMaxSize().then(if (locked) Modifier.clearAndSetSemantics {} else Modifier)) { content() }
            if (locked) LockScreen(lock, "Hearth is locked", "Unlock Hearth")
        }
    }
}

/**
 * Draws [content] behind a lock of its own, for one part of Hearth: [content] isn't started until [lock] is
 * unlocked, and the part locks again when it's closed. If it locks while open (after time away from Hearth),
 * the content stays alive underneath, so a shell or a page isn't lost.
 *
 * [name] is what's locked ("Terminal", an app's name). [appLock] is the lock in front of all of Hearth:
 * unlocking that while this part is in front unlocks it too, so nobody is asked twice in a row.
 * [onBack] is what Back does while locked; null leaves it to whatever is underneath.
 * [lockedPage], when given, stands in for the usual lock screen, and then nothing asks until that page does.
 */
@Composable
fun PartLockGate(
    lock: AppLock,
    appLock: AppLock,
    name: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    lockedPage: LockedPage? = null,
    content: @Composable () -> Unit,
) {
    val locked by lock.locked.collectAsStateWithLifecycle()
    val enabled by lock.enabled.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var opened by remember { mutableStateOf(!locked) }
    if (!locked) opened = true

    // While this part is on screen, it's what Recents would show: the LockGate around it keeps it out, as it
    // does for Lock Hearth (Android 13+). Leaving the part hands the choice back to Lock Hearth.
    val guardedPartsShown = LocalGuardedPartsShown.current
    DisposableEffect(enabled, guardedPartsShown) {
        if (enabled) guardedPartsShown.intValue++
        onDispose { if (enabled) guardedPartsShown.intValue-- }
    }

    // Closing the part locks it. Rotating recreates the screen, but the part is still open.
    DisposableEffect(lock) {
        onDispose { if (activity?.isChangingConfigurations != true) lock.lock() }
    }

    Box(modifier) {
        if (opened) Box(Modifier.fillMaxSize().then(if (locked) Modifier.clearAndSetSemantics {} else Modifier)) { content() }
        if (locked) LockScreen(lock, "$name is locked", "Unlock $name", appLock, onBack, lockedPage)
    }
}

/**
 * A part's own page for while it's locked, in place of the usual lock screen. `error` is why the last try to
 * unlock failed, if it did. `unlock` asks the phone to confirm it's the owner, unlocks the part, and then runs
 * what it's given: what the button that asked was for.
 */
typealias LockedPage = @Composable (error: String?, unlock: (then: () -> Unit) -> Unit) -> Unit

/**
 * The page covering what [lock] guards. [behind] is Hearth's own lock when this one is for a part of Hearth:
 * it asks first, and its answer counts here too. [page] is drawn in place of the padlock and its button, and
 * does the asking itself.
 */
@Composable
private fun LockScreen(
    lock: AppLock,
    heading: String,
    prompt: String,
    behind: AppLock? = null,
    onBack: (() -> Unit)? = null,
    page: LockedPage? = null,
) {
    val activity = LocalActivity.current as? FragmentActivity
    val owner = LocalLifecycleOwner.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var error by remember { mutableStateOf<String?>(null) }
    val waiting = behind?.locked?.collectAsStateWithLifecycle()?.value ?: false
    var waited by remember { mutableStateOf(waiting) }
    val asksItself = page == null

    fun ask(then: () -> Unit = {}) {
        val a = activity ?: return
        error = null
        a.confirmOwner(prompt, null, onSuccess = { lock.unlock(); then() }, onError = { error = it })
    }

    // Takes focus and the keyboard from whatever was being typed into (the terminal, a form).
    LaunchedEffect(Unit) {
        keyboard?.hide()
        runCatching { focus.requestFocus() }
    }
    // Asks once each time Hearth comes to the front locked. After a cancel, the button asks again.
    LaunchedEffect(owner, waiting, asksItself) {
        if (waiting) {
            waited = true
            return@LaunchedEffect
        }
        if (waited) {
            lock.unlock()
            return@LaunchedEffect
        }
        if (!asksItself) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            owner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            ask()
            awaitCancellation()
        }
    }
    if (behind == null) BackHandler { activity?.moveTaskToBack(true) } else if (onBack != null) BackHandler(onBack = onBack)

    // A Surface takes every touch, so nothing underneath can be tapped.
    Surface(Modifier.fillMaxSize().focusRequester(focus).focusable(), color = MaterialTheme.colorScheme.background) {
        if (page != null) page(error) { then -> ask(then) } else Column(
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
            Text(heading, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Button(onClick = { ask() }, Modifier.padding(top = 8.dp)) {
                Icon(Icons.Rounded.Fingerprint, null, Modifier.size(ButtonDefaults.IconSize))
                Text("Unlock", Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}
