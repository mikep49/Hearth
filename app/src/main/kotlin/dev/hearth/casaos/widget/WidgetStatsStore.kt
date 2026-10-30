package dev.hearth.casaos.widget

import android.content.Context
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.Credentials
import dev.hearth.casaos.data.SystemStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One stats reading as the widget shows it. Saved, so the widget can draw right away and while the server is offline. */
@Serializable
data class StatsSnapshot(
    /** The server it came from, so a reading never shows up after signing in to another one. */
    val baseUrl: String,
    val cpuPercent: Double,
    val cpuCores: Int,
    val cpuTempC: Int? = null,
    val memUsed: Long,
    val memTotal: Long,
    val memPercent: Double,
    val diskUsed: Long? = null,
    val diskTotal: Long? = null,
    val sampledAtMs: Long,
) {
    val diskPercent: Double?
        get() = if (diskUsed != null && diskTotal != null && diskTotal > 0) diskUsed * 100.0 / diskTotal else null

    companion object {
        fun of(stats: SystemStats, baseUrl: String) = StatsSnapshot(
            baseUrl = baseUrl,
            cpuPercent = stats.cpuPercent,
            cpuCores = stats.cpuCores,
            cpuTempC = stats.cpuTempC,
            memUsed = stats.memUsed,
            memTotal = stats.memTotal,
            memPercent = stats.memPercent,
            diskUsed = stats.disk?.usedBytes,
            diskTotal = stats.disk?.totalBytes,
            sampledAtMs = stats.sampledAtMs,
        )
    }
}

data class WidgetStats(
    val snapshot: StatsSnapshot? = null,
    /** Why the last refresh failed; cleared by the next one that works. */
    val error: String? = null,
    val refreshing: Boolean = false,
)

/** Where the widget's reading is kept between runs. An interface so [WidgetStatsStore] can be tested without Android. */
interface WidgetStorage {
    var saved: String?
}

class PrefsWidgetStorage(context: Context) : WidgetStorage {
    private val prefs = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
    override var saved: String?
        get() = prefs.getString(KEY, null)
        set(value) = prefs.edit().putString(KEY, value).apply()

    private companion object {
        const val KEY = "stats"
    }
}

/** Fetches and keeps the reading the server stats widget shows. */
class WidgetStatsStore(
    private val storage: WidgetStorage,
    private val client: CasaClient,
    private val credentials: Credentials,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var lastAttemptMs: Long? = null

    private val _state = MutableStateFlow(load())
    val state: StateFlow<WidgetStats> = _state.asStateFlow()

    /**
     * Fetches a new reading, unless one was tried in the last [maxAgeMs] (several widgets, or a widget and
     * the app, asking at once make one request). Does nothing while signed out.
     */
    suspend fun refresh(maxAgeMs: Long = 0) {
        mutex.withLock { fetch(maxAgeMs) }
    }

    private suspend fun fetch(maxAgeMs: Long) {
        val base = credentials.baseUrl ?: return
        val last = lastAttemptMs
        if (last != null && now() - last < maxAgeMs) return
        _state.update { it.copy(refreshing = true) }
        try {
            val next = try {
                WidgetStats(StatsSnapshot.of(client.utilization(), base))
            } catch (e: CasaException) {
                // Keep showing the last reading and say why it's not updating.
                WidgetStats(_state.value.snapshot?.takeIf { it.baseUrl == base }, e.message ?: "Can't reach your server")
            }
            lastAttemptMs = now()
            // Signed out (or into another server) while the request ran: that reading is no longer wanted.
            if (credentials.baseUrl == base) save(next)
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** Forgets the reading, e.g. on sign-out. */
    fun clear() {
        storage.saved = null
        lastAttemptMs = null
        _state.value = WidgetStats()
    }

    private fun save(stats: WidgetStats) {
        storage.saved = json.encodeToString(Saved.serializer(), Saved(stats.snapshot, stats.error))
        _state.value = stats
    }

    private fun load(): WidgetStats {
        // Unreadable (e.g. saved by another version) just means "no reading yet".
        val saved = storage.saved?.let { runCatching { json.decodeFromString(Saved.serializer(), it) }.getOrNull() }
        val snapshot = saved?.snapshot?.takeIf { it.baseUrl == credentials.baseUrl } ?: return WidgetStats()
        return WidgetStats(snapshot, saved.error)
    }

    @Serializable
    private class Saved(val snapshot: StatsSnapshot? = null, val error: String? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
