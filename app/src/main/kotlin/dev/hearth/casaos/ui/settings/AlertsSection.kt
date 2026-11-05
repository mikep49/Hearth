package dev.hearth.casaos.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.hearth.casaos.alerts.AlertNotifications
import dev.hearth.casaos.alerts.AlertSchedule
import dev.hearth.casaos.data.AlertSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import dev.hearth.casaos.ui.components.GroupFootnote
import dev.hearth.casaos.ui.components.ListGroup
import dev.hearth.casaos.ui.components.ListRow
import dev.hearth.casaos.ui.components.SwitchRow

@Composable
fun AlertsSection(settings: AlertSettings, onChange: (AlertSettings) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    // Checked again on return, in case notifications were just allowed in Android's settings.
    var allowed by remember { mutableStateOf(AlertNotifications.allowed(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = AlertNotifications.allowed(context)
        onPauseOrDispose {}
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = AlertNotifications.allowed(context)
        if (granted) onChange(settings.copy(enabled = true))
        else onError("Allow notifications for Hearth to get alerts")
    }

    fun toggle(on: Boolean) {
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !allowed) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onChange(settings.copy(enabled = on))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListGroup {
            SwitchRow(
                "Alerts",
                checked = settings.enabled,
                onCheckedChange = ::toggle,
                subtitle = "Get notified about your servers, even when Hearth is closed",
                icon = Icons.Rounded.NotificationsActive,
            )
            if (settings.enabled) {
                if (!allowed) {
                    ListRow(
                        "Notifications are off",
                        subtitle = "Turn them on for Hearth so alerts can show",
                        icon = Icons.Rounded.NotificationsOff,
                        iconTint = MaterialTheme.colorScheme.error,
                        trailing = {
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            }) { Text("Turn on") }
                        },
                    )
                }
                SwitchRow(
                    "Server offline",
                    checked = settings.offline,
                    onCheckedChange = { onChange(settings.copy(offline = it)) },
                    subtitle = "Can't be reached for about 15 minutes, including when your phone is off its network",
                    icon = Icons.Rounded.CloudOff,
                )
                SwitchRow(
                    "Storage almost full",
                    checked = settings.storage,
                    onCheckedChange = { onChange(settings.copy(storage = it)) },
                    subtitle = "Storage or a drive passes 90%",
                    icon = Icons.Rounded.Storage,
                )
                SwitchRow(
                    "App stopped",
                    checked = settings.appStopped,
                    onCheckedChange = { onChange(settings.copy(appStopped = it)) },
                    subtitle = "A running app stops or keeps restarting, and it wasn't stopped from Hearth",
                    icon = Icons.Rounded.StopCircle,
                )
                SwitchRow(
                    "App updates",
                    checked = settings.updates,
                    onCheckedChange = { onChange(settings.copy(updates = it)) },
                    subtitle = "The App Store has a new version of an installed app",
                    icon = Icons.Rounded.SystemUpdateAlt,
                )
            }
        }
        if (settings.enabled) {
            GroupFootnote(
                "Hearth checks every server you're signed in to about every ${AlertSchedule.EVERY_MINUTES} minutes. " +
                    "Android may check less often to save battery.",
            )
        }
    }
}
