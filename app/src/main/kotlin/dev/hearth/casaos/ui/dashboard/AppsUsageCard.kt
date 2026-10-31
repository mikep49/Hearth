package dev.hearth.casaos.ui.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hearth.casaos.data.AppUsage
import dev.hearth.casaos.data.SystemStats
import dev.hearth.casaos.ui.components.AppIcon
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.SectionLabel
import dev.hearth.casaos.ui.components.formatBytes
import dev.hearth.casaos.ui.theme.hearth
import kotlin.math.roundToInt

private const val COLLAPSED_ROWS = 5

private enum class SortBy(val label: String) { Cpu("CPU"), Memory("Memory") }

/** Like a task manager: what each app is using, busiest first. */
@Composable
internal fun AppsCard(
    state: AppsUsageState,
    stats: SystemStats,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    onOpenTerminal: () -> Unit,
) {
    var sort by rememberSaveable { mutableStateOf(SortBy.Cpu) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val arrow by animateFloatAsState(if (collapsed) 0f else 180f, label = "arrow")

    HearthCard(Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize()) {
            // The whole title row folds the card, so it's an easy target; the sort buttons keep their own taps.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (collapsed) "Show apps" else "Hide apps") { onCollapsedChange(!collapsed) }
                    .padding(start = 20.dp, end = 12.dp, top = if (collapsed) 16.dp else 20.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Apps", Modifier.weight(1f))
                if (!collapsed && state is AppsUsageState.Ready && state.apps.isNotEmpty()) {
                    SingleChoiceSegmentedButtonRow(Modifier.width(184.dp).height(36.dp)) {
                        SortBy.entries.forEachIndexed { i, s ->
                            SegmentedButton(
                                selected = sort == s,
                                onClick = { sort = s },
                                shape = SegmentedButtonDefaults.itemShape(i, SortBy.entries.size),
                                icon = {},
                                label = { Text(s.label, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }
                Icon(
                    Icons.Rounded.ExpandMore,
                    null,
                    Modifier.padding(start = 8.dp).size(22.dp).rotate(arrow),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!collapsed) AppsList(state, stats, sort, expanded, { expanded = it }, onOpenTerminal)
        }
    }
}

@Composable
private fun AppsList(
    state: AppsUsageState,
    stats: SystemStats,
    sort: SortBy,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenTerminal: () -> Unit,
) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (state) {
            AppsUsageState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Hint("Checking what each app is using…", Modifier.padding(start = 12.dp))
            }

            is AppsUsageState.Unavailable -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Hint(state.message)
                if (state.needsSignIn) {
                    FilledTonalButton(onClick = onOpenTerminal) {
                        Icon(Icons.Rounded.Terminal, null, Modifier.size(18.dp))
                        Text("Open Terminal", Modifier.padding(start = 8.dp))
                    }
                }
            }

            is AppsUsageState.Ready -> {
                if (state.apps.isEmpty()) {
                    Hint("No apps are running.")
                } else {
                    val sorted = when (sort) {
                        SortBy.Cpu -> state.apps.sortedByDescending { it.cpuPercent }
                        SortBy.Memory -> state.apps.sortedByDescending { it.memBytes }
                    }
                    val shown = if (expanded) sorted else sorted.take(COLLAPSED_ROWS)
                    shown.forEach { AppRow(it, stats, sort) }
                    if (sorted.size > COLLAPSED_ROWS) {
                        TextButton(onClick = { onExpandedChange(!expanded) }, Modifier.align(Alignment.CenterHorizontally)) {
                            Text(if (expanded) "Show fewer" else "Show all ${sorted.size} apps")
                        }
                    }
                }
                state.error?.let { Hint("$it · showing last reading", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppUsage, stats: SystemStats, sort: SortBy) {
    val c = MaterialTheme.hearth
    val cpu = serverCpuShare(app.cpuPercent, stats.cpuCores)
    val memFraction = if (stats.memTotal > 0) app.memBytes.toFloat() / stats.memTotal else 0f
    val cpuText = "${formatPercent(cpu)} CPU"
    val memText = formatBytes(app.memBytes)

    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app.iconUrl, app.name, size = 36.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The sorted-by figure leads; the other one sits beside it, dimmer.
                val (first, second) = if (sort == SortBy.Cpu) cpuText to memText else memText to cpuText
                Text(second, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(first, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            if (sort == SortBy.Cpu) UsageBar((cpu / 100).toFloat(), c.cpu) else UsageBar(memFraction, c.memory)
        }
    }
}

@Composable
internal fun UsageBar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = color)
}

/**
 * Docker counts 100% per core; the Home gauge shows the whole server. Dividing by the core count makes the
 * two agree, so an app at 25% here is a quarter of the server's CPU.
 */
internal fun serverCpuShare(dockerPercent: Double, cores: Int): Double =
    if (cores > 0) dockerPercent / cores else dockerPercent

internal fun formatPercent(p: Double): String = when {
    p <= 0.0 -> "0%"
    p < 1.0 -> "<1%"
    p < 10.0 -> "${(p * 10).roundToInt() / 10.0}%"
    else -> "${p.roundToInt()}%"
}
