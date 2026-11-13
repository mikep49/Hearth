package dev.hearth.casaos.ui.settings

import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.hearth.casaos.data.Launcher
import dev.hearth.casaos.ui.components.Chevron
import dev.hearth.casaos.ui.components.ListRow
import dev.hearth.casaos.ui.components.SwitchRow

/** Android answers faster than this only when it didn't ask at all. */
private const val ANSWERED_UNASKED_MS = 400L

/**
 * The switch that makes Hearth the home screen, as rows for the Android group in Settings. It shows what
 * Android says, since the home app can be changed in Android's settings at any time.
 */
@Composable
fun LauncherRows(launcher: Launcher, onError: (String) -> Unit) {
    val activity = LocalActivity.current
    var isHome by remember { mutableStateOf(launcher.isHome()) }
    var askedAt by rememberSaveable { mutableLongStateOf(0L) }

    fun settle() {
        launcher.choosing = false
        // With a yes, this Hearth was opened from the old home screen and makes way for the one that now is the home screen.
        isHome = activity?.let(launcher::settle) ?: launcher.isHome()
    }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { settle() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // After "Don't ask again" Android says no by itself, at once. Its settings page can still make the change.
        val unasked = !launcher.isHome() && SystemClock.elapsedRealtime() - askedAt < ANSWERED_UNASKED_MS
        if (unasked && runCatching { choose.launch(launcher.chooseIntent()) }.isSuccess) return@rememberLauncherForActivityResult
        settle()
    }
    // Checked again on return, in case the home app was changed in Android's settings.
    LifecycleResumeEffect(launcher) {
        isHome = launcher.isHome()
        onPauseOrDispose {}
    }

    fun toggle(on: Boolean) {
        if (!on) {
            activity?.let(launcher::leave)
            isHome = false
            return
        }
        val intent = launcher.askIntent() ?: return
        launcher.setOffered(true)
        launcher.choosing = true
        askedAt = SystemClock.elapsedRealtime()
        if (runCatching { ask.launch(intent) }.isFailure) {
            settle()
            onError("Couldn't ask Android to change the home screen")
        }
    }

    SwitchRow(
        "Use as home screen",
        checked = isHome,
        onCheckedChange = ::toggle,
        subtitle = if (isHome) "Hearth opens at startup and when you press Home" else "For a tablet kept just for your server",
        icon = Icons.Rounded.Home,
    )
    // With no app list to open them from, this is the way to Android's settings and, through them, other apps.
    if (isHome) {
        ListRow(
            "Android settings",
            icon = Icons.Rounded.Settings,
            trailing = { Chevron() },
            onClick = { activity?.let(launcher::openAndroidSettings) },
        )
    }
}
