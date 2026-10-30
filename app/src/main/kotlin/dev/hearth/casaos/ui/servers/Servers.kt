package dev.hearth.casaos.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hearth.casaos.data.ServerProfile
import dev.hearth.casaos.ui.components.HearthCard

/** One saved server: its address, the account, and whether it's signed in. */
@Composable
fun ServerRow(
    profile: ServerProfile,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Surface(onClick = onClick, modifier = modifier, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    Text(
                        profile.label.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(profile.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val user = profile.username.ifBlank { "Account" }
                Text(
                    if (profile.signedIn) user else "$user · signed out",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (profile.signedIn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing()
        }
    }
}

/**
 * Lists the saved servers to switch between, with a way to add one. Picking a signed-out server asks
 * [onSignInAgain] to show the sign-in form for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(
    profiles: List<ServerProfile>,
    currentId: String?,
    onSwitch: (ServerProfile) -> Unit,
    onSignInAgain: (ServerProfile) -> Unit,
    onAdd: () -> Unit,
    onRemove: (ServerProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var removing by remember { mutableStateOf<ServerProfile?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Servers", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.headlineSmall)
            HearthCard(Modifier.fillMaxWidth()) {
                profiles.forEachIndexed { i, profile ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    ServerRow(
                        profile,
                        selected = profile.id == currentId,
                        onClick = {
                            when {
                                profile.id == currentId -> onDismiss()
                                profile.signedIn -> onSwitch(profile)
                                else -> onSignInAgain(profile)
                            }
                        },
                        trailing = {
                            IconButton(onClick = { removing = profile }) {
                                Icon(Icons.Rounded.Close, "Remove ${profile.label}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
                if (profiles.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Surface(onClick = onAdd, color = Color.Transparent) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Text("Add a server", Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(
                "Each server keeps its own login. Home screen widgets can show any of them: tap a widget's server name to pick.",
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    removing?.let { profile ->
        RemoveServerDialog(profile, onConfirm = { onRemove(profile); removing = null }, onDismiss = { removing = null })
    }
}

@Composable
fun RemoveServerDialog(profile: ServerProfile, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${profile.label}?") },
        text = { Text("Hearth signs out of this server and forgets it. You can add it again later.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
