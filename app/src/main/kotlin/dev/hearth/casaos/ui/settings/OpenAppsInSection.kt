package dev.hearth.casaos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dev.hearth.casaos.data.OpenAppsIn
import dev.hearth.casaos.ui.components.HearthCard

@Composable
fun OpenAppsInSection(value: OpenAppsIn, onChange: (OpenAppsIn) -> Unit) {
    val haptic = LocalHapticFeedback.current
    HearthCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Open apps in", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                OpenAppsIn.entries.forEachIndexed { i, option ->
                    SegmentedButton(
                        selected = value == option,
                        onClick = {
                            if (value != option) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onChange(option)
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, OpenAppsIn.entries.size),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = value == option) {
                                Icon(
                                    when (option) {
                                        OpenAppsIn.Hearth -> Icons.Rounded.Public
                                        OpenAppsIn.Browser -> Icons.AutoMirrored.Rounded.OpenInNew
                                    },
                                    null,
                                    Modifier.size(SegmentedButtonDefaults.IconSize),
                                )
                            }
                        },
                    ) { Text(option.label, maxLines = 1) }
                }
            }
        }
    }
}
