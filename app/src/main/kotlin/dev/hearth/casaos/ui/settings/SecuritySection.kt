package dev.hearth.casaos.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.hearth.casaos.data.LockAfter
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.lock.confirmOwner

@Composable
fun SecuritySection(
    enabled: Boolean,
    lockAfter: LockAfter,
    available: () -> Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onLockAfterChange: (LockAfter) -> Unit,
    onError: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val activity = LocalActivity.current as? FragmentActivity
    // Checked again on return, in case a screen lock was just set up in the phone's settings.
    var canLock by remember { mutableStateOf(available()) }
    LifecycleResumeEffect(Unit) {
        canLock = available()
        onPauseOrDispose {}
    }

    // Turning it on or off asks first, so it's the owner (and the phone's lock works) either way.
    fun toggle(on: Boolean) {
        val a = activity ?: return
        a.confirmOwner(
            if (on) "Turn on lock" else "Turn off lock",
            "Confirm it's you",
            onSuccess = { onEnabledChange(on) },
            onError = { it?.let(onError) },
        )
    }

    HearthCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, enabled = canLock || enabled, role = Role.Switch, onValueChange = ::toggle)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Fingerprint, null)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("Lock Hearth", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (canLock) "Ask for your fingerprint, face or phone PIN to open Hearth"
                    else "Set up a screen lock on your phone first, in Android's Settings › Security",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = null, enabled = canLock || enabled)
        }
        if (enabled) {
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Lock again after leaving", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LockAfter.entries.forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = lockAfter == option,
                            onClick = {
                                if (lockAfter != option) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onLockAfterChange(option)
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, LockAfter.entries.size),
                        ) { Text(option.label, maxLines = 1) }
                    }
                }
                Text(
                    "Hearth always starts locked, and so do apps opened from home screen icons. The widget still shows your server's stats.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
