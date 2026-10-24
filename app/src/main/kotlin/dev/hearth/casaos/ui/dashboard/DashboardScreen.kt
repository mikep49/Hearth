package dev.hearth.casaos.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.SystemStats
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.RingGauge
import dev.hearth.casaos.ui.components.SectionLabel
import dev.hearth.casaos.ui.components.Sparkline
import dev.hearth.casaos.ui.components.formatBytes
import dev.hearth.casaos.ui.components.formatRate
import dev.hearth.casaos.ui.theme.hearth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Calendar

private const val HISTORY = 40
private const val POLL_MS = 2_500L

data class DashboardState(
    val stats: SystemStats? = null,
    val cpuHistory: List<Float> = emptyList(),
    val rxHistory: List<Float> = emptyList(),
    val txHistory: List<Float> = emptyList(),
    val rxRate: Long = 0,
    val txRate: Long = 0,
    val error: String? = null,
)

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
    fun List<Float>.push(v: Float) = (this + v).takeLast(HISTORY)
    return copy(
        stats = next,
        cpuHistory = cpuHistory.push(next.cpuPercent.toFloat() / 100f),
        rxHistory = rxHistory.push(rx.toFloat()),
        txHistory = txHistory.push(tx.toFloat()),
        rxRate = rx,
        txRate = tx,
        error = null,
    )
}

class DashboardViewModel(private val client: CasaClient) : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    /** Runs until cancelled; the screen scopes it to the STARTED lifecycle so it stops in the background. */
    suspend fun poll() {
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
}

@Composable
fun DashboardScreen(viewModel: DashboardViewModel, username: String, serverLabel: String) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats = state.stats

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(username, serverLabel)

        AnimatedVisibility(state.error != null) {
            HearthCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.error)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Can't reach your server", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.error.orEmpty() + (if (stats != null) " · showing last reading" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (stats == null) {
            if (state.error == null) {
                Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            } else {
                EmptyState("No data yet", "We'll keep trying while this screen is open.")
            }
            return@Column
        }

        Overview(stats)
        CpuCard(stats, state.cpuHistory)
        NetworkCard(state)
        MemoryCard(stats)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header(username: String, serverLabel: String) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            if (username.isBlank()) greeting else "$greeting, $username",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(serverLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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

@Composable
private fun CpuCard(stats: SystemStats, history: List<Float>) {
    val color = MaterialTheme.hearth.cpu
    HearthCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("Processor")
                    Text(
                        "${stats.cpuPercent.toInt()}%",
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 30.sp),
                        modifier = Modifier.padding(top = 4.dp),
                    )
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
            }
            Sparkline(history, color, Modifier.fillMaxWidth().height(72.dp), max = 1f)
        }
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
        Text(formatRate(rate), style = MaterialTheme.typography.titleMedium)
        Sparkline(history, color, Modifier.fillMaxWidth().height(44.dp))
    }
}

@Composable
private fun MemoryCard(stats: SystemStats) {
    HearthCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Memory")
                Text(
                    "${formatBytes(stats.memUsed)} of ${formatBytes(stats.memTotal)}",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            stats.disk?.let {
                Column(horizontalAlignment = Alignment.End) {
                    SectionLabel("Storage")
                    Text(
                        "${formatBytes(it.usedBytes)} of ${formatBytes(it.totalBytes)}",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
