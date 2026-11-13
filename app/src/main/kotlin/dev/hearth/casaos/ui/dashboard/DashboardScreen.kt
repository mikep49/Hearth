package dev.hearth.casaos.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearth.casaos.data.AppUsage
import dev.hearth.casaos.data.AppUsageSource
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.ContainerUsage
import dev.hearth.casaos.data.DiskStats
import dev.hearth.casaos.data.Drive
import dev.hearth.casaos.data.HomeWidget
import dev.hearth.casaos.data.InstalledApp
import dev.hearth.casaos.data.ServerRoute
import dev.hearth.casaos.data.SshUnavailable
import dev.hearth.casaos.data.groupUsage
import dev.hearth.casaos.data.homeSections
import dev.hearth.casaos.data.SystemStats
import dev.hearth.casaos.ui.components.CardPadding
import dev.hearth.casaos.ui.components.CardRadius
import dev.hearth.casaos.ui.components.Chevron
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.ListRow
import dev.hearth.casaos.ui.components.ScreenHeader
import dev.hearth.casaos.ui.components.ReadableWidth
import dev.hearth.casaos.ui.components.SectionLabel
import dev.hearth.casaos.ui.components.Sparkline
import dev.hearth.casaos.ui.components.formatBytes
import dev.hearth.casaos.ui.components.formatRate
import dev.hearth.casaos.ui.components.readableWidth
import dev.hearth.casaos.ui.theme.hearth
import java.util.Calendar
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update

private const val POLL_MS = 2_500L

/** How many processor readings are kept: five minutes' worth, which the Task manager shows in full. */
internal const val CPU_HISTORY = 120

/** How many readings the graphs on Home show: the last two minutes. */
internal const val HOME_HISTORY = 48
private const val APPS_POLL_MS = 3_000L
private const val APPS_LIST_MS = 60_000L
/** Each reading runs smartctl on every drive, and health and free space change slowly. */
private const val DRIVES_POLL_MS = 60_000L

data class DashboardState(
    val stats: SystemStats? = null,
    val cpuHistory: List<Float> = emptyList(),
    val rxHistory: List<Float> = emptyList(),
    val txHistory: List<Float> = emptyList(),
    val rxRate: Long = 0,
    val txRate: Long = 0,
    val error: String? = null,
    val apps: AppsUsageState = AppsUsageState.Loading,
    /** Null until the first reading, and on servers without the disks API. */
    val drives: List<Drive>? = null,
)

/** The Task manager's list of apps, which comes over SSH and so can be unavailable while the server totals still work. */
sealed interface AppsUsageState {
    data object Loading : AppsUsageState

    /** [needsSignIn]: the Terminal tab can fix it by saving an SSH sign-in. */
    data class Unavailable(val message: String, val needsSignIn: Boolean) : AppsUsageState

    /** [error] is set when the latest reading failed and [apps] is the last good one. */
    data class Ready(val apps: List<AppUsage>, val error: String? = null) : AppsUsageState
}

/** A failed reading keeps the last list on screen; with nothing to show, it replaces it. */
internal fun AppsUsageState.failed(e: SshUnavailable): AppsUsageState =
    if (this is AppsUsageState.Ready && !e.needsSignIn) copy(error = e.message)
    else AppsUsageState.Unavailable(e.message.orEmpty(), e.needsSignIn)

internal fun DashboardState.advance(next: SystemStats): DashboardState {
    val prev = stats
    var rx = 0L
    var tx = 0L
    if (prev != null) {
        val dt = (next.sampledAtMs - prev.sampledAtMs) / 1000.0
        if (dt > 0) {
            // Counters can reset (interface restart); clamp so the graph never dips negative.
            rx = ((next.netRxBytes - prev.netRxBytes) / dt).toLong().coerceAtLeast(0)
            tx = ((next.netTxBytes - prev.netTxBytes) / dt).toLong().coerceAtLeast(0)
        }
    }
    fun List<Float>.push(v: Float, keep: Int) = (this + v).takeLast(keep)
    return copy(
        stats = next,
        cpuHistory = cpuHistory.push(next.cpuPercent.toFloat() / 100f, CPU_HISTORY),
        rxHistory = rxHistory.push(rx.toFloat(), HOME_HISTORY),
        txHistory = txHistory.push(tx.toFloat(), HOME_HISTORY),
        rxRate = rx,
        txRate = tx,
        error = null,
    )
}

class DashboardViewModel(
    private val client: CasaClient,
    private val usage: AppUsageSource,
) : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    /** Only the Task manager shows what each app uses, so that's only read while it's open. */
    private val taskManagerOpen = MutableStateFlow(false)

    fun setTaskManagerOpen(open: Boolean) {
        taskManagerOpen.value = open
    }

    /** Installed apps, for names and icons. Refreshed now and then, and when an unknown app shows up. */
    private var installed: List<InstalledApp>? = null
    private var installedAtMs = 0L

    /** Runs until cancelled; the screen scopes it to the STARTED lifecycle so it stops in the background. */
    suspend fun poll() = coroutineScope {
        launch { pollServer() }
        launch { pollDrives() }
        // Closing the Task manager stops the per-app readings and closes their SSH login.
        launch { taskManagerOpen.collectLatest { open -> if (open) pollApps() } }
    }

    private suspend fun pollServer() {
        while (true) {
            try {
                val sample = client.utilization()
                _state.update { it.advance(sample) }
            } catch (e: CasaException) {
                // Keep the last good sample on screen and flag staleness instead of blanking the dashboard.
                _state.update { it.copy(error = e.message) }
            }
            delay(POLL_MS)
        }
    }

    private suspend fun pollDrives() {
        while (true) {
            try {
                val drives = client.drives()
                _state.update { it.copy(drives = drives) }
            } catch (e: CasaException) {
                // Keep the last list; the server banner already reports a connection problem.
            }
            delay(DRIVES_POLL_MS)
        }
    }

    private suspend fun pollApps() {
        try {
            while (true) {
                try {
                    val containers = usage.sample()
                    val apps = installedApps(containers)
                    _state.update { it.copy(apps = AppsUsageState.Ready(groupUsage(containers, apps))) }
                } catch (e: SshUnavailable) {
                    _state.update { it.copy(apps = it.apps.failed(e)) }
                }
                // `docker stats` itself takes about two seconds, so this refreshes every five or so.
                delay(APPS_POLL_MS)
            }
        } finally {
            usage.close() // don't hold an SSH login open while nothing shows the list
        }
    }

    private suspend fun installedApps(containers: List<ContainerUsage>): List<InstalledApp> {
        val known = installed
        val now = System.currentTimeMillis()
        val unknown = known != null && containers.any { c -> c.project != null && known.none { it.id == c.project } }
        if (known != null && !unknown && now - installedAtMs < APPS_LIST_MS) return known
        return try {
            client.apps().also { installed = it; installedAtMs = now }
        } catch (e: CasaException) {
            known.orEmpty() // names fall back to container names until the next try
        }
    }
}

/**
 * [route] is how Hearth reaches the server, which picks the icon beside its address. [widgets] is what the
 * page shows under that, in order, as arranged in Settings › Appearance.
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    username: String,
    serverLabel: String,
    route: ServerRoute,
    widgets: List<HomeWidget>,
    onOpenServers: () -> Unit,
    onOpenTaskManager: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats = state.stats
    // Read again with each reading, so a Hearth left open all day, as a tablet's home screen is, keeps up with the clock.
    val hello = greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))

    // Wide screens put cards side by side rather than all in one column.
    BoxWithConstraints {
        val twoColumns = maxWidth >= TwoColumnWidth
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .readableWidth(if (twoColumns) TwoColumnMaxWidth else ReadableWidth)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(if (username.isBlank()) hello else "$hello, $username")

            val link = when {
                state.error != null -> Link.Offline
                stats == null -> Link.Connecting
                else -> Link.Connected
            }
            ConnectionBar(serverLabel, route, link, onOpenServers)

            ServerUnreachable(state)

            if (stats == null) {
                if (state.error == null) {
                    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                    }
                } else {
                    EmptyState("No data yet", "We'll keep trying while this screen is open.", icon = Icons.Rounded.CloudOff)
                }
                return@Column
            }

            val drives = state.drives.orEmpty()
            // A widget this server has no reading for is left out, and the ones around it close up.
            val sections = homeSections(widgets.filter { it.hasReading(stats, drives) })
            if (sections.isEmpty()) {
                Text(
                    "Nothing is on Home. Add widgets in Settings › Appearance › Customize Home.",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            val card: @Composable (HomeWidget) -> Unit = { widget ->
                when (widget) {
                    HomeWidget.ProcessorGraph -> CpuCard(stats, state.cpuHistory, onOpenTaskManager)
                    HomeWidget.Network -> NetworkCard(state)
                    HomeWidget.MemoryBar -> MemoryCard(stats)
                    HomeWidget.Drives -> if (drives.isNotEmpty()) StorageCard(drives, stats.disk) else stats.disk?.let { StorageTotalCard(it) }
                    else -> Unit // tiles are drawn by StatTiles
                }
            }
            sections.forEach { section ->
                when {
                    section.tiles -> StatTiles(stats, section.widgets, onOpenTaskManager)
                    // Side by side: the first half of the cards down the left, the rest down the right.
                    twoColumns && section.widgets.size > 1 -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val left = (section.widgets.size + 1) / 2
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            section.widgets.take(left).forEach { card(it) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            section.widgets.drop(left).forEach { card(it) }
                        }
                    }
                    else -> section.widgets.forEach { card(it) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Whether this server has anything for the widget to show. */
internal fun HomeWidget.hasReading(stats: SystemStats, drives: List<Drive>): Boolean = when (this) {
    HomeWidget.TemperatureTile -> stats.cpuTempC != null
    HomeWidget.StorageTile -> stats.disk != null
    HomeWidget.Drives -> drives.isNotEmpty() || stats.disk != null
    else -> true
}

/** Shown while the server isn't answering. Any readings still on screen are the last ones it gave. */
@Composable
internal fun ColumnScope.ServerUnreachable(state: DashboardState) {
    AnimatedVisibility(state.error != null) {
        ListRow(
            "Can't reach your server",
            subtitle = state.error.orEmpty() + (if (state.stats != null) " · showing last reading" else ""),
            icon = Icons.Rounded.CloudOff,
            iconTint = MaterialTheme.colorScheme.error,
            shape = RoundedCornerShape(CardRadius),
        )
    }
}

/** From this width Home shows two columns of cards, up to [TwoColumnMaxWidth] across. */
private val TwoColumnWidth = 840.dp
private val TwoColumnMaxWidth = 1200.dp

/** The greeting for [hour] of the day, 0 to 23. */
internal fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/** Whether the server is answering. */
private enum class Link { Connecting, Connected, Offline }

/**
 * The bar under the greeting: whether the server is answering, and the address Hearth reaches it at.
 * Tapping it switches to another server.
 */
@Composable
private fun ConnectionBar(address: String, route: ServerRoute, link: Link, onOpenServers: () -> Unit) {
    val (label, color) = when (link) {
        Link.Connecting -> "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
        Link.Connected -> "Connected" to MaterialTheme.hearth.good
        Link.Offline -> "Offline" to MaterialTheme.hearth.bad
    }
    val icon = when {
        link == Link.Offline -> Icons.Rounded.CloudOff
        route == ServerRoute.Tailscale -> Icons.Rounded.VpnLock
        route == ServerRoute.LocalNetwork -> Icons.Rounded.Wifi
        else -> Icons.Rounded.Public
    }
    HearthCard(
        Modifier.fillMaxWidth().semantics { onClick("Switch server") { onOpenServers(); true } },
        onClick = onOpenServers,
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = color)
            Text(label, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
            Text(
                address,
                Modifier.weight(1f).padding(start = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Rounded.ExpandMore, null, Modifier.padding(start = 4.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Tapping it opens the Task manager, which has the same graph over a longer time and what each app uses. */
@Composable
private fun CpuCard(stats: SystemStats, history: List<Float>, onOpenTaskManager: () -> Unit) {
    HearthCard(Modifier.fillMaxWidth(), onClick = onOpenTaskManager) {
        Column(Modifier.padding(start = 20.dp, top = 20.dp, end = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProcessorHeader(stats) { Chevron() }
            UsageChart(history, HOME_HISTORY, MaterialTheme.hearth.cpu, Modifier.fillMaxWidth().padding(end = 8.dp).height(80.dp))
        }
    }
}

/** The processor's load now, with its core count, temperature and model. [trailing] ends the row. */
@Composable
internal fun ProcessorHeader(stats: SystemStats, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            SectionLabel("Processor")
            MetricValue(formatCpuTotal(stats.cpuPercent))
        }
        Column(horizontalAlignment = Alignment.End) {
            val parts = buildList {
                if (stats.cpuCores > 0) add("${stats.cpuCores} cores")
                stats.cpuTempC?.let { add("$it°C") }
            }
            if (parts.isNotEmpty()) {
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (stats.cpuModel.isNotBlank()) {
                Text(stats.cpuModel.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        trailing()
    }
}

@Composable
private fun NetworkCard(state: DashboardState) {
    val c = MaterialTheme.hearth
    HearthCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("Network")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RateColumn("Download", state.rxRate, state.rxHistory, c.download, Icons.Rounded.ArrowDownward, Modifier.weight(1f))
                RateColumn("Upload", state.txRate, state.txHistory, c.upload, Icons.Rounded.ArrowUpward, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RateColumn(
    label: String,
    rate: Long,
    history: List<Float>,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = color)
            Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(formatRate(rate), style = MaterialTheme.typography.titleLarge)
        Sparkline(history, color, Modifier.fillMaxWidth().height(44.dp), slots = HOME_HISTORY)
    }
}

/** A card's headline figure, e.g. CPU use, the same size on every card. */
@Composable
internal fun MetricValue(value: String, unit: String? = null) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        if (unit != null) {
            Text(
                unit,
                Modifier.padding(start = 6.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MemoryCard(stats: SystemStats) {
    HearthCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding)) {
            UsedOf("Memory", stats.memUsed, stats.memTotal, MaterialTheme.hearth.memory, "${stats.memPercent.toInt()}% used")
        }
    }
}

/** The Drives widget on servers that can't list each drive: the system disk's total. */
@Composable
private fun StorageTotalCard(disk: DiskStats) {
    HearthCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding)) {
            UsedOf("Storage", disk.usedBytes, disk.totalBytes, MaterialTheme.hearth.storage, "${disk.percent.toInt()}% used")
        }
    }
}

/** e.g. MEMORY  6.4 GB of 16 GB, with a bar. */
@Composable
internal fun UsedOf(label: String, used: Long, total: Long, color: Color, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                SectionLabel(label)
                MetricValue(formatBytes(used), "of ${formatBytes(total)}")
            }
            Text(detail, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        UsageBar(if (total > 0) used.toFloat() / total else 0f, color, height = 8.dp)
    }
}

/** A thin bar filled to [fraction] of its width. */
@Composable
internal fun UsageBar(fraction: Float, color: Color, height: Dp = 4.dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(50)).background(color))
    }
}
