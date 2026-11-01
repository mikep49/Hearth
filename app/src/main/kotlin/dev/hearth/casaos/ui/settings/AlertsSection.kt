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
import dev.hearth.casaos.ui.components.HearthCard

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

    HearthCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = settings.enabled, role = Role.Switch, onValueChange = ::toggle)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.NotificationsActive, null)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("Alerts", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Get notified about your servers, even when Hearth is closed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.enabled, onCheckedChange = null)
        }
        if (settings.enabled) AlertOptions(settings, allowed, onChange)
    }
}

@Composable
private fun AlertOptions(settings: AlertSettings, allowed: Boolean, onChange: (AlertSettings) -> Unit) {
    val context = LocalContext.current
    Column {
        if (!allowed) {
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Notifications are off for Hearth, so alerts can't be shown",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }) { Text("Turn on") }
            }
        }

        HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
        AlertRow(
            "Server offline",
            "When it can't be reached for about 15 minutes. That includes when your phone is off the server's network.",
            settings.offline,
        ) { onChange(settings.copy(offline = it)) }
        AlertRow("Storage almost full", "When storage or a drive passes 90%", settings.storage) { onChange(settings.copy(storage = it)) }
        AlertRow(
            "App stopped",
            "When a running app stops or keeps restarting, and it wasn't stopped from Hearth",
            settings.appStopped,
        ) { onChange(settings.copy(appStopped = it)) }
        AlertRow("App updates", "When the App Store has a new version of an installed app", settings.updates) {
            onChange(settings.copy(updates = it))
        }
        Text(
            "Hearth checks every server you're signed in to about every ${AlertSchedule.EVERY_MINUTES} minutes. " +
                "Android may check less often to save battery.",
            Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlertRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
