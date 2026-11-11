package dev.hearth.casaos.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.hearth.casaos.ui.components.CardPadding
import dev.hearth.casaos.ui.components.CardRadius
import dev.hearth.casaos.ui.components.GroupFootnote
import dev.hearth.casaos.ui.components.GroupRowShape
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.ListGroup
import dev.hearth.casaos.ui.components.SectionHeader
import dev.hearth.casaos.ui.components.SwitchRow
import dev.hearth.casaos.ui.theme.Palette
import dev.hearth.casaos.ui.theme.ThemeMode
import dev.hearth.casaos.ui.theme.ThemeSettings
import dev.hearth.casaos.ui.theme.UiStyle
import dev.hearth.casaos.ui.theme.isDark

/** What the Settings row for the Appearance page says is chosen, e.g. "Dark · Ember · One UI". */
val ThemeSettings.summary: String
    get() = listOf(mode.label, palette.label, style.label).joinToString(" · ")

/** Everything on the Appearance page: light or dark, the accent color, and the Android or One UI style. */
@Composable
fun AppearanceSection(settings: ThemeSettings, onChange: (ThemeSettings) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val dark = settings.isDark()
    fun change(next: ThemeSettings) {
        if (next != settings) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onChange(next)
    }

    SectionHeader("Theme")
    ListGroup {
        HearthCard(Modifier.fillMaxWidth(), shape = GroupRowShape) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(CardPadding)) {
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
        }
        SwitchRow(
            "Pure black",
            checked = settings.pureBlack,
            onCheckedChange = { change(settings.copy(pureBlack = it)) },
            subtitle = if (dark) "Black background for OLED screens" else "Only in dark theme",
            icon = Icons.Rounded.Contrast,
            enabled = dark,
        )
    }

    SectionHeader("Accent color")
    HearthCard(Modifier.fillMaxWidth()) {
        // Swatches share the width evenly so all of them are always visible, whatever the screen size.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = CardPadding).selectableGroup()) {
            Palette.available.forEach { palette ->
                Swatch(palette, dark, selected = settings.palette == palette, Modifier.weight(1f)) {
                    change(settings.copy(palette = palette))
                }
            }
        }
    }

    SectionHeader("Style")
    // Both cards as tall as the taller one, in case one's text wraps.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        UiStyle.entries.forEach { style ->
            StyleCard(style, selected = settings.style == style, Modifier.weight(1f).fillMaxHeight()) { change(settings.copy(style = style)) }
        }
    }
    GroupFootnote("Changes the tabs, lists and switches. Your theme and accent color stay the same.")
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

/** One of the two styles to pick from: a small picture of it, its name and what sets it apart. */
@Composable
private fun StyleCard(style: UiStyle, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val edge by animateColorAsState(if (selected) scheme.primary else Color.Transparent, label = "edge")
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.semantics { role = Role.RadioButton },
        shape = RoundedCornerShape(CardRadius),
        color = scheme.surfaceContainer,
        border = BorderStroke(2.dp, edge),
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StylePreview(style, Modifier.fillMaxWidth().height(132.dp))
            Text(style.label, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                when (style) {
                    UiStyle.Android -> "Tab bar, separate rows"
                    UiStyle.OneUi -> "Floating tabs, joined rows"
                },
                Modifier.padding(top = 2.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A small picture of a screen in [style], in the theme's own colors: a title, a list, and the tabs. */
@Composable
private fun StylePreview(style: UiStyle, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val frame = RoundedCornerShape(14.dp)
    Canvas(modifier.clip(frame).background(scheme.background).border(1.dp, scheme.outlineVariant, frame)) {
        val oneUi = style == UiStyle.OneUi
        val side = 10.dp.toPx()

        drawRoundRect(scheme.onSurface, Offset(side, 12.dp.toPx()), Size(size.width * 0.36f, 6.dp.toPx()), CornerRadius(3.dp.toPx()))

        // The tabs: a bar right across the bottom, or a pill floating above it.
        val barHeight = 20.dp.toPx()
        val barLeft = if (oneUi) side else 0f
        val barTop = size.height - barHeight - if (oneUi) 8.dp.toPx() else 0f
        val barWidth = size.width - barLeft * 2
        drawRoundRect(scheme.surfaceContainerHighest, Offset(barLeft, barTop), Size(barWidth, barHeight), CornerRadius(if (oneUi) barHeight / 2 else 0f))
        val tabs = 4
        val inset = if (oneUi) 2.dp.toPx() else 0f
        val slot = (barWidth - inset * 2) / tabs
        repeat(tabs) { i ->
            val center = Offset(barLeft + inset + slot * (i + 0.5f), barTop + barHeight / 2)
            if (i == 0) {
                // The open tab: a small marker in the bar, or one that fills its share of the pill.
                val mark = if (oneUi) Size(slot, barHeight - inset * 2) else Size(20.dp.toPx(), 10.dp.toPx())
                drawRoundRect(
                    scheme.secondaryContainer,
                    Offset(center.x - mark.width / 2, center.y - mark.height / 2),
                    mark,
                    CornerRadius(mark.height / 2),
                )
            }
            drawCircle(if (i == 0) scheme.onSecondaryContainer else scheme.onSurfaceVariant, 2.5.dp.toPx(), center)
        }

        // The list: rows a little apart, or joined into one card with lines between them.
        val rows = 3
        val listTop = 28.dp.toPx()
        val listHeight = barTop - 10.dp.toPx() - listTop
        val gap = if (oneUi) 0f else 2.dp.toPx()
        val rowHeight = (listHeight - gap * (rows - 1)) / rows
        val outer = CornerRadius(8.dp.toPx())
        val inner = CornerRadius(if (oneUi) 0f else 2.dp.toPx())
        repeat(rows) { i ->
            val top = listTop + (rowHeight + gap) * i
            val row = Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(Offset(side, top), Size(size.width - side * 2, rowHeight)),
                        topLeft = if (i == 0) outer else inner,
                        topRight = if (i == 0) outer else inner,
                        bottomRight = if (i == rows - 1) outer else inner,
                        bottomLeft = if (i == rows - 1) outer else inner,
                    ),
                )
            }
            drawPath(row, scheme.surfaceContainerHigh)
            if (oneUi && i > 0) {
                drawLine(scheme.outline, Offset(side + 8.dp.toPx(), top), Offset(size.width - side - 8.dp.toPx(), top), 1.dp.toPx())
            }
            val middle = top + rowHeight / 2
            drawCircle(scheme.primary, 4.dp.toPx(), Offset(side + 12.dp.toPx(), middle))
            drawRoundRect(
                scheme.onSurfaceVariant,
                Offset(side + 22.dp.toPx(), middle - 2.dp.toPx()),
                Size(size.width * (0.42f - 0.08f * i), 4.dp.toPx()),
                CornerRadius(2.dp.toPx()),
            )
        }
    }
}
