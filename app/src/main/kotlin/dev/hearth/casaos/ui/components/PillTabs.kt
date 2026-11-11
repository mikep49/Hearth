package dev.hearth.casaos.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** One tab of [PillTabs]. Its icon is outlined, and filled in while the tab is open. */
@Immutable
class PillTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** The space between the pill's edge and its tabs. */
private val PillInset = 4.dp

/** How tall each tab is, and how wide when the pill stands on its end. */
private val PillTabHeight = 56.dp
private val PillTabWidth = 64.dp

/**
 * The tabs as a floating pill, as in One UI: lying along the bottom of a phone, or standing down the
 * side of a wider screen when [vertical]. A highlight slides to the open tab.
 */
@Composable
fun PillTabs(
    tabs: List<PillTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    // Read only while drawing, so sliding the highlight redraws the pill without recomposing it.
    val position = animateFloatAsState(
        selected.toFloat(),
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "tab",
    )
    val highlight = Modifier.drawBehind {
        val at = position.value
        if (vertical) {
            val h = size.height / tabs.size
            drawRoundRect(scheme.secondaryContainer, Offset(0f, at * h), Size(size.width, h), CornerRadius(minOf(size.width, h) / 2))
        } else {
            val w = size.width / tabs.size
            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - (at + 1) * w else at * w
            drawRoundRect(scheme.secondaryContainer, Offset(x, 0f), Size(w, size.height), CornerRadius(minOf(w, size.height) / 2))
        }
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = scheme.surfaceContainerHigh,
        // The shadow hardly shows on a dark background, so a thin edge marks the pill out as well.
        border = BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = 8.dp,
    ) {
        if (vertical) {
            Column(Modifier.padding(PillInset).width(PillTabWidth).then(highlight).selectableGroup()) {
                tabs.forEachIndexed { i, tab ->
                    PillTabItem(tab, selected == i, { onSelect(i) }, Modifier.fillMaxWidth().height(PillTabHeight))
                }
            }
        } else {
            Row(Modifier.padding(PillInset).height(PillTabHeight).then(highlight).selectableGroup()) {
                tabs.forEachIndexed { i, tab ->
                    PillTabItem(tab, selected == i, { onSelect(i) }, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun PillTabItem(tab: PillTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val color by animateColorAsState(if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant, label = "tabColor")
    Column(
        modifier.clip(CircleShape).selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (selected) tab.selectedIcon else tab.icon, null, Modifier.size(22.dp), tint = color)
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
