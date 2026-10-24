package dev.hearth.casaos.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.theme.Palette
import dev.hearth.casaos.ui.theme.ThemeMode
import dev.hearth.casaos.ui.theme.ThemeSettings
import dev.hearth.casaos.ui.theme.isDark

@Composable
fun AppearanceSection(settings: ThemeSettings, onChange: (ThemeSettings) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val dark = settings.isDark()
    fun change(next: ThemeSettings) {
        if (next != settings) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onChange(next)
    }

    HearthCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = settings.mode == mode,
                        onClick = { change(settings.copy(mode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = settings.mode == mode) {
                                Icon(
                                    when (mode) {
                                        ThemeMode.System -> Icons.Rounded.PhoneAndroid
                                        ThemeMode.Light -> Icons.Rounded.LightMode
                                        ThemeMode.Dark -> Icons.Rounded.DarkMode
                                    },
                                    null,
                                    Modifier.size(SegmentedButtonDefaults.IconSize),
                                )
                            }
                        },
                    ) { Text(mode.label, maxLines = 1) }
                }
            }

            Text("Accent", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium)
            // Swatches share the width evenly so all of them are always visible, whatever the screen size.
            Row(Modifier.fillMaxWidth()) {
                Palette.available.forEach { palette ->
                    Swatch(palette, dark, selected = settings.palette == palette, Modifier.weight(1f)) {
                        change(settings.copy(palette = palette))
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = settings.pureBlack, enabled = dark, role = Role.Switch) {
                    change(settings.copy(pureBlack = !settings.pureBlack))
                }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                val tint = if (dark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                Text("Pure black", style = MaterialTheme.typography.titleMedium, color = tint)
                Text(
                    if (dark) "True black backgrounds. Easier on OLED screens and battery." else "Only applies in dark mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The row handles the toggle so the whole line is one large touch target.
            Switch(checked = settings.pureBlack, onCheckedChange = null, enabled = dark)
        }
    }
}

@Composable
private fun Swatch(palette: Palette, dark: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val color: Color = if (palette == Palette.Wallpaper && Palette.dynamicSupported) {
        (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
    } else {
        if (dark) palette.dark.primary else palette.light.primary
    }
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, label = "ring")
    val onColor = if (color.luminance() > 0.5f) Color.Black else Color.White

    Column(
        modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .border(ring, MaterialTheme.colorScheme.onSurface, CircleShape)
                .padding(if (selected) 5.dp else 0.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected -> Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = onColor)
                palette == Palette.Wallpaper -> Icon(Icons.Rounded.Wallpaper, null, Modifier.size(18.dp), tint = onColor)
            }
        }
        Text(
            palette.label,
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
