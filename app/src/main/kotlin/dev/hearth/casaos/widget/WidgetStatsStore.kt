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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** One stats reading as the widget shows it. Saved, so the widget can draw right away and while the server is offline. */
@Serializable
data class StatsSnapshot(
    /** The server it came from, so a reading never shows up for another one. */
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

/** Where the widgets' readings and server choices are kept between runs. An interface so the stores can be tested without Android. */
interface WidgetStorage {
    var saved: String?
}

class PrefsWidgetStorage(context: Context, private val key: String = "stats_by_server") : WidgetStorage {
    private val prefs = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
    override var saved: String?
        get() = prefs.getString(key, null)
        set(value) = prefs.edit().putString(key, value).apply()
}

/** Fetches and keeps the reading each server's widgets show, by saved server id. */
class WidgetStatsStore(
    private val storage: WidgetStorage,
    private val credentialsFor: (String) -> Credentials,
    private val clientFor: (String) -> CasaClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // One lock per server, so a server that's offline (and slow to time out) doesn't hold up the others.
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastAttemptMs = ConcurrentHashMap<String, Long>()

    private val _state = MutableStateFlow(load())
    /** Readings by server id. A server with no reading yet is missing. */
    val state: StateFlow<Map<String, WidgetStats>> = _state.asStateFlow()

    /**
     * Fetches a new reading for [server], unless one was tried in the last [maxAgeMs] (several widgets, or a
     * widget and the app, asking at once make one request). Does nothing while that server is signed out.
     */
    suspend fun refresh(server: String, maxAgeMs: Long = 0) {
        locks.getOrPut(server) { Mutex() }.withLock { fetch(server, maxAgeMs) }
    }

    private suspend fun fetch(id: String, maxAgeMs: Long) {
        val credentials = credentialsFor(id)
        val base = credentials.baseUrl ?: return
        val last = lastAttemptMs[id]
        if (last != null && now() - last < maxAgeMs) return
        _state.update { it + (id to (it[id] ?: WidgetStats()).copy(refreshing = true)) }
        try {
            val next = try {
                WidgetStats(StatsSnapshot.of(clientFor(id).utilization(), base))
            } catch (e: CasaException) {
                // Keep showing the last reading and say why it's not updating.
                WidgetStats(_state.value[id]?.snapshot?.takeIf { it.baseUrl == base }, e.message ?: "Can't reach your server")
            }
            lastAttemptMs[id] = now()
            // Signed out while the request ran: that reading is no longer wanted.
            if (credentials.baseUrl == base) save(id, next)
        } finally {
            _state.update { all -> all[id]?.let { all + (id to it.copy(refreshing = false)) } ?: all }
        }
    }

    /** Forgets [server]'s reading, e.g. when it's signed out. */
    fun clear(server: String) {
        lastAttemptMs.remove(server)
        _state.update { it - server }
        persist()
    }

    private fun save(id: String, stats: WidgetStats) {
        _state.update { it + (id to stats) }
        persist()
    }

    @Synchronized
    private fun persist() {
        val all = _state.value.mapValues { (_, v) -> Saved(v.snapshot, v.error) }
        storage.saved = json.encodeToString(savedMap, all)
    }

    private fun load(): Map<String, WidgetStats> {
        // Unreadable (e.g. saved by another version) just means "no reading yet".
        val saved = storage.saved?.let { runCatching { json.decodeFromString(savedMap, it) }.getOrNull() }.orEmpty()
        return saved.mapNotNull { (id, s) ->
            val base = credentialsFor(id).baseUrl ?: return@mapNotNull null
            id to WidgetStats(s.snapshot?.takeIf { it.baseUrl == base }, s.error)
        }.toMap()
    }

    @Serializable
    private class Saved(val snapshot: StatsSnapshot? = null, val error: String? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val savedMap = MapSerializer(String.serializer(), Saved.serializer())
    }
}

/**
 * Which saved server each placed widget shows, by app widget id. A widget with no choice shows whichever
 * server is open in Hearth.
 */
class WidgetServerChoices(private val storage: WidgetStorage) {
    private val _choices = MutableStateFlow(load())
    val choices: StateFlow<Map<Int, String>> = _choices.asStateFlow()

    /** [server] null follows the app again. */
    fun set(widgetId: Int, server: String?) {
        _choices.update { if (server == null) it - widgetId else it + (widgetId to server) }
        storage.saved = json.encodeToString(choiceMap, _choices.value.mapKeys { it.key.toString() })
    }

    /** Drops choices for widgets that were removed from the home screen. */
    fun keepOnly(widgetIds: Set<Int>) {
        if (_choices.value.keys.all { it in widgetIds }) return
        _choices.update { it.filterKeys { id -> id in widgetIds } }
        storage.saved = json.encodeToString(choiceMap, _choices.value.mapKeys { it.key.toString() })
    }

    private fun load(): Map<Int, String> =
        storage.saved?.let { runCatching { json.decodeFromString(choiceMap, it) }.getOrNull() }.orEmpty()
            .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()

    private companion object {
        val json = Json
        val choiceMap = MapSerializer(String.serializer(), String.serializer())
    }
}
