package dev.hearth.casaos.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hearth.casaos.data.DiskStats
import dev.hearth.casaos.data.Drive
import dev.hearth.casaos.data.DriveHealth
import dev.hearth.casaos.data.DriveKind
import dev.hearth.casaos.data.Volume
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.IconTile
import dev.hearth.casaos.ui.components.SectionLabel
import dev.hearth.casaos.ui.components.formatBytes
import dev.hearth.casaos.ui.theme.hearth

/** Each drive on the server: what it is, whether SMART reports a problem, and how full its volumes are. */
@Composable
internal fun StorageCard(drives: List<Drive>, total: DiskStats?, modifier: Modifier = Modifier.fillMaxWidth()) {
    HearthCard(modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Storage", Modifier.weight(1f))
                total?.let {
                    Text(
                        "${formatBytes(it.usedBytes)} of ${formatBytes(it.totalBytes)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            drives.forEach { DriveRow(it) }
        }
    }
}

@Composable
private fun DriveRow(drive: Drive) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                when (drive.kind) {
                    DriveKind.System -> Icons.Rounded.Dns
                    DriveKind.Usb -> Icons.Rounded.Usb
                    else -> Icons.Rounded.Storage
                },
                tint = MaterialTheme.hearth.storage,
                size = 36.dp,
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(driveTitle(drive), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    driveDetails(drive),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HealthBadge(drive.health)
        }
        Column(Modifier.padding(start = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (drive.volumes.isEmpty()) {
                Text("Not mounted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            drive.volumes.forEach { VolumeRow(it) }
        }
    }
}

@Composable
private fun VolumeRow(volume: Volume) {
    val c = MaterialTheme.hearth
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                volume.mountPoint,
                Modifier.weight(1f).padding(end = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (volume.totalBytes > 0) "${formatBytes(volume.usedBytes)} of ${formatBytes(volume.totalBytes)}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val color = when {
            volume.percent >= 90 -> c.bad
            volume.percent >= 80 -> c.warn
            else -> c.storage
        }
        UsageBar((volume.percent / 100).toFloat(), color)
    }
}

@Composable
private fun HealthBadge(health: DriveHealth) {
    val (label, color) = when (health) {
        DriveHealth.Healthy -> "Healthy" to MaterialTheme.hearth.good
        DriveHealth.Failing -> "Failing" to MaterialTheme.hearth.bad
        DriveHealth.Unknown -> return
    }
    Text(
        label,
        Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

internal fun driveTitle(drive: Drive): String = when {
    drive.kind == DriveKind.System -> "System drive"
    drive.model != null -> drive.model
    drive.kind == DriveKind.Usb -> "USB drive"
    else -> "Drive ${drive.name}"
}

/** e.g. `HDD · 4.0 TB · 38°C · sda`. */
internal fun driveDetails(drive: Drive): String = buildList {
    if (drive.kind != DriveKind.System) add(drive.kind.label)
    if (drive.sizeBytes > 0) add(formatBytes(drive.sizeBytes))
    drive.temperatureC?.let { add("$it°C") }
    if (drive.kind != DriveKind.System) add(drive.name)
}.joinToString(" · ")
