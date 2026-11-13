package dev.hearth.casaos.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import dev.hearth.casaos.data.Drive
import dev.hearth.casaos.data.InstalledApp
import dev.hearth.casaos.data.SshUnavailable
import dev.hearth.casaos.data.groupUsage
import dev.hearth.casaos.data.SystemStats
import dev.hearth.casaos.ui.components.CardPadding
import dev.hearth.casaos.ui.components.CardRadius
import dev.hearth.casaos.ui.components.Chevron
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.ListRow
import dev.hearth.casaos.ui.components.ScreenHeader
import dev.hearth.casaos.ui.components.StatusDot
import dev.hearth.casaos.ui.components.ReadableWidth
import dev.hearth.casaos.ui.components.RingGauge
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

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    username: String,
    serverLabel: String,
    onOpenServers: () -> Unit,
    onOpenTaskManager: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats = state.stats

    // Wide screens put memory and storage beside the gauges and graphs rather than below them.
    BoxWithConstraints {
        val twoColumns = maxWidth >= TwoColumnWidth
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .readableWidth(if (twoColumns) TwoColumnMaxWidth else ReadableWidth)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(username, serverLabel, online = state.error == null && stats != null, onOpenServers)

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
            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Overview(stats)
                        CpuCard(stats, state.cpuHistory, onOpenTaskManager)
                        NetworkCard(state)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        MemoryCard(stats, showStorage = drives.isEmpty())
                        if (drives.isNotEmpty()) StorageCard(drives, stats.disk)
                    }
                }
            } else {
                Overview(stats)
                CpuCard(stats, state.cpuHistory, onOpenTaskManager)
                NetworkCard(state)
                MemoryCard(stats, showStorage = drives.isEmpty())
                if (drives.isNotEmpty()) StorageCard(drives, stats.disk)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
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

@Composable
private fun Header(username: String, serverLabel: String, online: Boolean, onOpenServers: () -> Unit) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
    ScreenHeader(
        if (username.isBlank()) greeting else "$greeting, $username",
        subtitle = {
            // The server's name, with whether it's answering; tapping it switches to another server.
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Switch server", onClick = onOpenServers).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(if (online) MaterialTheme.hearth.good else MaterialTheme.hearth.bad)
                Text(serverLabel, Modifier.padding(start = 8.dp))
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
            }
        },
    )
}

@Composable
private fun Overview(stats: SystemStats) {
    val c = MaterialTheme.hearth
    HearthCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Gauge("CPU", stats.cpuPercent, c.cpu)
            Gauge("Memory", stats.memPercent, c.memory)
            stats.disk?.let { Gauge("Storage", it.percent, c.storage) }
        }
    }
}

@Composable
private fun Gauge(label: String, percent: Double, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RingGauge(fraction = (percent / 100).toFloat(), color = color, size = 92.dp) {
            Text("${percent.toInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** [showStorage]: the storage total too, for servers where the Storage card can't show each drive. */
@Composable
private fun MemoryCard(stats: SystemStats, showStorage: Boolean) {
    val c = MaterialTheme.hearth
    HearthCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            UsedOf("Memory", stats.memUsed, stats.memTotal, c.memory, "${stats.memPercent.toInt()}% used")
            stats.disk?.takeIf { showStorage }?.let {
                UsedOf("Storage", it.usedBytes, it.totalBytes, c.storage, "${it.percent.toInt()}% used")
            }
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
