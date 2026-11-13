package dev.hearth.casaos.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearth.casaos.data.AppUsage
import dev.hearth.casaos.data.ContainerUsage
import dev.hearth.casaos.data.SystemStats
import dev.hearth.casaos.ui.components.AppIcon
import dev.hearth.casaos.ui.components.BackButton
import dev.hearth.casaos.ui.components.CardPadding
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.SectionLabel
import dev.hearth.casaos.ui.components.formatBytes
import dev.hearth.casaos.ui.components.hearthTopBarColors
import dev.hearth.casaos.ui.components.readableWidth
import dev.hearth.casaos.ui.theme.hearth
import kotlin.math.roundToInt

/** The columns of the list of apps. Tapping a column's title sorts by it. */
private enum class TaskSort(val title: String) { Name("App"), Cpu("CPU"), Memory("Memory") }

private val CpuColumn = 68.dp
private val MemoryColumn = 80.dp

/** How far a row's text starts from the card's edge: the side padding, the app's icon and the gap after it. */
private val RowTextStart = 68.dp

/** The apps in the order the Task manager lists them: by name from A to Z, or by use with the heaviest first. */
private fun List<AppUsage>.sortedFor(sort: TaskSort): List<AppUsage> = when (sort) {
    TaskSort.Name -> sortedBy { it.name.lowercase() }
    TaskSort.Cpu -> sortedByDescending { it.cpuPercent }
    TaskSort.Memory -> sortedByDescending { it.memBytes }
}

/**
 * Opened from the Processor card on Home: the processor's load over the last five minutes, memory, and
 * what every app and each of its containers is using. It's the only place that shows usage per app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskManagerScreen(
    viewModel: DashboardViewModel,
    onClose: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats = state.stats

    // Home, underneath, keeps reading the server. The list of apps is only read while this page is open.
    DisposableEffect(viewModel) {
        viewModel.setTaskManagerOpen(true)
        onDispose { viewModel.setTaskManagerOpen(false) }
    }
    BackHandler(onBack = onClose)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onClose) },
                title = { Text("Task manager") },
                colors = hearthTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readableWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServerUnreachable(state)
            if (stats == null) {
                // Only after switching to a server that hasn't answered yet.
                if (state.error == null) {
                    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                    }
                }
                return@Column
            }
            ProcessorCard(stats, state.cpuHistory)
            HearthCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(CardPadding)) {
                    UsedOf("Memory", stats.memUsed, stats.memTotal, MaterialTheme.hearth.memory, "${stats.memPercent.toInt()}% used")
                }
            }
            AppsTable(state.apps, stats, onOpenTerminal)
        }
    }
}

@Composable
private fun ProcessorCard(stats: SystemStats, history: List<Float>) {
    HearthCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProcessorHeader(stats)
            UsageChart(history, CPU_HISTORY, MaterialTheme.hearth.cpu, Modifier.fillMaxWidth().height(176.dp), labelMiddle = true)
            Row {
                Caption("Last 5 minutes", Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    Caption("Average ${formatCpuTotal(history.average() * 100)} · Peak ${formatCpuTotal(history.max() * 100.0)}")
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

/** Every app with its share of the processor and its memory. An app with several containers opens to show each one. */
@Composable
private fun AppsTable(state: AppsUsageState, stats: SystemStats, onOpenTerminal: () -> Unit) {
    var sort by rememberSaveable { mutableStateOf(TaskSort.Cpu) }
    var opened by rememberSaveable { mutableStateOf(emptySet<String>()) }

    HearthCard(Modifier.fillMaxWidth()) {
        val apps = (state as? AppsUsageState.Ready)?.apps.orEmpty()
        if (apps.isEmpty()) {
            Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionLabel("Apps")
                if (state is AppsUsageState.Ready) Hint("No apps are running.") else AppsNotReady(state, onOpenTerminal)
                StaleReading(state)
            }
            return@HearthCard
        }

        val sorted = remember(apps, sort) { apps.sortedFor(sort) }
        Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ColumnTitle(TaskSort.Name, sort, { sort = it }, Modifier.weight(1f), Arrangement.Start)
                ColumnTitle(TaskSort.Cpu, sort, { sort = it }, Modifier.width(CpuColumn), Arrangement.End)
                ColumnTitle(TaskSort.Memory, sort, { sort = it }, Modifier.width(MemoryColumn), Arrangement.End)
            }
            sorted.forEach { app ->
                key(app.id) {
                    val several = app.containers.size > 1
                    val open = several && app.id in opened
                    AppRow(app, stats, sort, open, onToggle = if (several) ({ opened = if (open) opened - app.id else opened + app.id }) else null)
                    AnimatedVisibility(open) {
                        Column { app.containers.forEach { ContainerRow(it, stats) } }
                    }
                }
            }
            if (state is AppsUsageState.Ready && state.error != null) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) { StaleReading(state) }
            }
        }
    }
}

/** Says the list is the last one read, when the newest reading failed. */
@Composable
private fun StaleReading(state: AppsUsageState) {
    val error = (state as? AppsUsageState.Ready)?.error ?: return
    Hint("$error · showing last reading", color = MaterialTheme.colorScheme.error)
}

/** A column's title. The column the list is sorted by is in the accent colour, with an arrow. */
@Composable
private fun ColumnTitle(
    column: TaskSort,
    sort: TaskSort,
    onSort: (TaskSort) -> Unit,
    modifier: Modifier,
    arrangement: Arrangement.Horizontal,
) {
    val selected = column == sort
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Sort by ${column.title}") { onSort(column) }
            .heightIn(min = 40.dp)
            // The arrow has blank space of its own on its far side, so the text still lines up with the rows.
            .padding(start = 8.dp, end = if (selected) 2.dp else 8.dp),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(column.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, letterSpacing = 1.2.sp, maxLines = 1)
        // Names run from A up; the figures run from the largest down.
        if (selected) Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(16.dp).rotate(if (column == TaskSort.Name) 180f else 0f), tint = color)
    }
}

@Composable
private fun AppRow(app: AppUsage, stats: SystemStats, sort: TaskSort, open: Boolean, onToggle: (() -> Unit)?) {
    val c = MaterialTheme.hearth
    val cpu = serverCpuShare(app.cpuPercent, stats.cpuCores)
    val memFraction = if (stats.memTotal > 0) app.memBytes.toFloat() / stats.memTotal else 0f
    val toggle = if (onToggle != null) {
        Modifier.clickable(onClickLabel = if (open) "Hide containers" else "Show containers", onClick = onToggle)
    } else Modifier

    Row(Modifier.fillMaxWidth().then(toggle).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app.iconUrl, app.name, size = 36.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(app.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onToggle != null) {
                val arrow by animateFloatAsState(if (open) 180f else 0f, label = "arrow")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${app.containers.size} containers",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp).rotate(arrow), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        UsageCell(formatPercent(cpu), (cpu / 100).toFloat(), c.cpu, CpuColumn, strong = sort == TaskSort.Cpu)
        UsageCell(formatBytes(app.memBytes), memFraction, c.memory, MemoryColumn, strong = sort == TaskSort.Memory)
    }
}

/** One figure of an app's row, with a bar under it for its share of the whole server. */
@Composable
private fun UsageCell(text: String, fraction: Float, color: Color, width: Dp, strong: Boolean) {
    Column(Modifier.width(width).padding(start = 14.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = if (strong) FontWeight.Bold else null, maxLines = 1)
        UsageBar(fraction, color)
    }
}

/** One container of an opened app, in the same columns as the apps. */
@Composable
private fun ContainerRow(container: ContainerUsage, stats: SystemStats) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelMedium
    Row(Modifier.fillMaxWidth().padding(start = RowTextStart, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(container.name, Modifier.weight(1f), style = style, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            formatPercent(serverCpuShare(container.cpuPercent, stats.cpuCores)),
            Modifier.width(CpuColumn),
            style = style,
            color = muted,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Text(
            formatBytes(container.memBytes),
            Modifier.width(MemoryColumn),
            style = style,
            color = muted,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/** What stands in for the list of apps while it's loading, or when it can't be read. */
@Composable
private fun AppsNotReady(state: AppsUsageState, onOpenTerminal: () -> Unit) {
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

        is AppsUsageState.Ready -> Unit
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = color)
}

/**
 * Docker counts 100% per core; the Processor graph shows the whole server. Dividing by the core count makes
 * the two agree, so an app at 25% here is a quarter of the server's CPU.
 */
internal fun serverCpuShare(dockerPercent: Double, cores: Int): Double =
    if (cores > 0) dockerPercent / cores else dockerPercent

internal fun formatPercent(p: Double): String = when {
    p <= 0.0 -> "0%"
    p < 1.0 -> "<1%"
    p < 10.0 -> "${(p * 10).roundToInt() / 10.0}%"
    else -> "${p.roundToInt()}%"
}
