package dev.hearth.casaos.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hearth.casaos.data.HomeWidget
import dev.hearth.casaos.data.SystemStats
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.RingGauge
import dev.hearth.casaos.ui.components.SectionLabel
import dev.hearth.casaos.ui.components.formatBytes
import dev.hearth.casaos.ui.theme.hearth

private val TileGap = 12.dp
private val TilePadding = 16.dp

/** From this width the tiles go four across; narrower than that, each would be too small for its figures. */
private val FourTilesWidth = 636.dp

/** Tiles are square up to this height, then only get wider, so a tablet doesn't give half its screen to them. */
private val MaxTileHeight = 200.dp

/** How warm the processor is, which sets the colour of its tile. */
internal enum class Heat { Cool, Warm, Hot }

/** Under load a healthy processor sits in the 60s and 70s; from 80°C up it's close to slowing itself down. */
internal fun heat(tempC: Int): Heat = when {
    tempC < 65 -> Heat.Cool
    tempC < 80 -> Heat.Warm
    else -> Heat.Hot
}

/** CasaOS names the processor by its maker, in lower case: "intel", "amd" or "arm". Other servers' names are left alone. */
internal fun cpuName(model: String): String = when (model.trim().lowercase()) {
    "intel" -> "Intel"
    "amd" -> "AMD"
    "arm" -> "ARM"
    else -> model.trim()
}

/**
 * A run of Home's square tiles, in the order given: processor, its temperature, memory or storage. They
 * go two to a row, or four on a wide screen, and tiles short of a full row share it between them.
 */
@Composable
internal fun StatTiles(stats: SystemStats, widgets: List<HomeWidget>, onOpenTaskManager: () -> Unit) {
    val c = MaterialTheme.hearth
    val tiles = widgets.mapNotNull<HomeWidget, @Composable (Modifier) -> Unit> { widget ->
        when (widget) {
            HomeWidget.CpuTile -> { modifier ->
                UsageTile(
                    "CPU", cpuName(stats.cpuModel), formatCpuTotal(stats.cpuPercent), stats.cpuPercent, c.cpu, modifier,
                    caption = if (stats.cpuCores > 0) "${stats.cpuCores} cores" else null,
                    onClick = onOpenTaskManager,
                )
            }
            HomeWidget.TemperatureTile -> stats.cpuTempC?.let { temp -> { modifier -> TemperatureTile(temp, modifier) } }
            HomeWidget.MemoryTile -> { modifier ->
                UsageTile(
                    "Memory", "${formatBytes(stats.memUsed)} of ${formatBytes(stats.memTotal)}",
                    "${stats.memPercent.toInt()}%", stats.memPercent, c.memory, modifier,
                )
            }
            HomeWidget.StorageTile -> stats.disk?.let { disk ->
                { modifier ->
                    UsageTile(
                        "Storage", "${formatBytes(disk.usedBytes)} of ${formatBytes(disk.totalBytes)}",
                        "${disk.percent.toInt()}%", disk.percent, c.storage, modifier,
                    )
                }
            }
            else -> null // cards are drawn by the page
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= FourTilesWidth) 4 else 2
        val side = minOf((maxWidth - TileGap * (columns - 1)) / columns, MaxTileHeight)
        Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
            tiles.chunked(columns).forEach { row ->
                // The row is as tall as its tallest tile, so large text grows both tiles rather than one.
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight().heightIn(min = side)) }
                }
            }
        }
    }
}

/** A tile for something that fills up: what it is at the top, how full it is at the bottom, as a figure and a ring. */
@Composable
private fun UsageTile(
    label: String,
    detail: String,
    value: String,
    percent: Double,
    color: Color,
    modifier: Modifier,
    caption: String? = null,
    onClick: (() -> Unit)? = null,
) {
    HearthCard(modifier, onClick = onClick) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(TilePadding), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                SectionLabel(label)
                if (detail.isNotBlank()) {
                    Text(detail, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    if (caption != null) {
                        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(value, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
                }
                RingGauge((percent / 100).toFloat(), color, Modifier.padding(bottom = 2.dp), size = 48.dp, stroke = 6.dp)
            }
        }
    }
}

/** The processor's temperature, large, on a glow that goes from green to amber to red as it warms up. */
@Composable
private fun TemperatureTile(tempC: Int, modifier: Modifier) {
    val c = MaterialTheme.hearth
    val tint by animateColorAsState(
        when (heat(tempC)) { Heat.Cool -> c.good; Heat.Warm -> c.warn; Heat.Hot -> c.bad },
        label = "heat",
    )
    HearthCard(modifier.clearAndSetSemantics { contentDescription = "CPU temperature, $tempC degrees Celsius" }) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .drawBehind {
                    // Strongest in the bottom corner under the figure's side, gone by the opposite corner.
                    drawRect(
                        Brush.linearGradient(
                            listOf(tint.copy(alpha = 0.32f), tint.copy(alpha = 0.08f), Color.Transparent),
                            start = Offset(0f, size.height),
                            end = Offset(size.width, 0f),
                        ),
                    )
                }
                .padding(TilePadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("CPU")
                    Text("Temperature", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("°C", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "$tempC",
                Modifier.align(Alignment.End).padding(top = 8.dp),
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Medium),
                color = tint,
                maxLines = 1,
            )
        }
    }
}
