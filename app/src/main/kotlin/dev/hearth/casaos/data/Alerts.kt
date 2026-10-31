package dev.hearth.casaos.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Which alerts are on. Alerts as a whole are off until turned on in Settings. */
data class AlertSettings(
    val enabled: Boolean = false,
    val offline: Boolean = true,
    val storage: Boolean = true,
    val appStopped: Boolean = true,
    val updates: Boolean = true,
) {
    /** Whether there's anything to check for. */
    val active: Boolean get() = enabled && (offline || storage || appStopped || updates)
}

/** One background check of a server. */
sealed interface AlertReading {
    data class Online(
        val apps: List<InstalledApp>?,
        /** How full storage is, in percent: the total under [TOTAL_STORAGE], and each volume by mount point when read. */
        val volumes: Map<String, Double>?,
    ) : AlertReading

    data object Offline : AlertReading
}

/** Something to tell the user about. */
sealed interface Alert {
    data object ServerOffline : Alert
    /** Clears [ServerOffline]. */
    data object BackOnline : Alert
    data class StorageFull(val volume: String, val percent: Double) : Alert
    data class AppStopped(val appId: String, val appName: String, val restarting: Boolean) : Alert
    data class UpdatesAvailable(val appNames: List<String>) : Alert
}

/** What the last checks of one server found, so each problem is reported once rather than every 15 minutes. */
@Serializable
data class ServerAlertState(
    val failures: Int = 0,
    val offlineNotified: Boolean = false,
    /** Each app's state at the last check, by app id. Empty before the first check. */
    val apps: Map<String, AppState> = emptyMap(),
    /** Apps already reported as stopped; cleared once they run again. */
    val stoppedNotified: Set<String> = emptySet(),
    /** Volumes already reported as full; cleared once they drop back under [STORAGE_CLEAR]. */
    val fullNotified: Set<String> = emptySet(),
    /** Apps whose current update was already reported. */
    val updatesNotified: Set<String> = emptySet(),
    /** Apps stopped or restarted from Hearth, with when, so that stop isn't reported. */
    val expectedStops: Map<String, Long> = emptyMap(),
    /** When each drive's volumes were last read. That makes CasaOS run smartctl, which can wake sleeping disks. */
    val drivesCheckedAtMs: Long = 0,
)

/** The total storage, as opposed to one volume, in [AlertReading.Online.volumes] and [Alert.StorageFull]. */
const val TOTAL_STORAGE = ""
/** How often the background check reads each drive's volumes. Disks fill slowly, and the check can wake them. */
const val DRIVES_EVERY_MS = 6 * 60 * 60 * 1000L

/** Failed checks in a row before the server counts as offline, so one dropped request doesn't alert. */
const val OFFLINE_AFTER = 2
const val STORAGE_FULL = 90.0
/** A full volume alerts again only after dropping below this, so hovering around 90% doesn't keep alerting. */
const val STORAGE_CLEAR = 85.0
/** How long a stop made from Hearth stays expected. Long enough to cover the next check or two. */
const val EXPECTED_STOP_MS = 60 * 60 * 1000L

/**
 * Compares a new [reading] with what the last checks found and returns the new state with the alerts to show.
 * Alerts that are off still update the state, so turning one on later doesn't report old news.
 */
fun evaluateAlerts(
    prev: ServerAlertState,
    reading: AlertReading,
    settings: AlertSettings,
    nowMs: Long,
): Pair<ServerAlertState, List<Alert>> {
    val alerts = mutableListOf<Alert>()
    val expected = prev.expectedStops.filterValues { nowMs - it < EXPECTED_STOP_MS }

    if (reading is AlertReading.Offline) {
        val failures = prev.failures + 1
        val notify = settings.offline && failures >= OFFLINE_AFTER && !prev.offlineNotified
        if (notify) alerts += Alert.ServerOffline
        return prev.copy(failures = failures, offlineNotified = prev.offlineNotified || notify, expectedStops = expected) to alerts
    }
    reading as AlertReading.Online
    if (prev.offlineNotified) alerts += Alert.BackOnline
    var next = prev.copy(failures = 0, offlineNotified = false, expectedStops = expected)

    reading.volumes?.let { volumes ->
        val full = volumes.filterValues { it >= STORAGE_FULL }
        val fresh = full.filterKeys { it !in prev.fullNotified }
        if (settings.storage) fresh.forEach { (volume, percent) -> alerts += Alert.StorageFull(volume, percent) }
        // Stays reported until it's clearly below the line again. Drives are read less often than the total,
        // so a volume missing from this reading keeps its state.
        val still = prev.fullNotified.filter { volumes[it]?.let { p -> p >= STORAGE_CLEAR } ?: true }.toSet()
        next = next.copy(fullNotified = still + fresh.keys)
    }

    reading.apps?.let { apps ->
        val stopped = mutableSetOf<String>()
        val handled = mutableSetOf<String>()
        for (app in apps) {
            val was = prev.apps[app.id]
            // Stuck restarting across two checks means it keeps crashing.
            val crashed = (was == AppState.Running && app.state == AppState.Stopped) ||
                (was == AppState.Restarting && app.state == AppState.Restarting)
            when {
                app.state == AppState.Running -> Unit
                app.id in prev.stoppedNotified -> stopped += app.id
                crashed && app.id in expected -> handled += app.id
                crashed -> {
                    if (settings.appStopped) alerts += Alert.AppStopped(app.id, app.name, restarting = app.state == AppState.Restarting)
                    stopped += app.id
                }
            }
        }
        // A stop that was seen is used up; others wait for a later check until they expire.
        val keep = expected - handled

        val withUpdates = apps.filter { it.updateAvailable }
        val newUpdates = withUpdates.filter { it.id !in prev.updatesNotified }
        if (settings.updates && newUpdates.isNotEmpty()) alerts += Alert.UpdatesAvailable(newUpdates.map { it.name })

        next = next.copy(
            apps = apps.associate { it.id to it.state },
            stoppedNotified = stopped,
            updatesNotified = withUpdates.map { it.id }.toSet(),
            expectedStops = keep,
        )
    }
    return next to alerts
}

/** Where [AlertStore] keeps its state. An interface so it can be tested without Android. */
interface AlertStorage {
    var saved: String?
}

/** The alert state of each saved server, by server id. Shared by the background check and the app. */
class AlertStore(private val storage: AlertStorage) {
    private var states: Map<String, ServerAlertState> =
        storage.saved?.let { runCatching { json.decodeFromString(map, it) }.getOrNull() }.orEmpty()

    @Synchronized
    fun get(server: String): ServerAlertState = states[server] ?: ServerAlertState()

    @Synchronized
    fun set(server: String, state: ServerAlertState) {
        states = states + (server to state)
        persist()
    }

    /** Replaces [server]'s state with what [change] makes of it, in one step, and returns what [change] returns. */
    @Synchronized
    fun <T> update(server: String, change: (ServerAlertState) -> Pair<ServerAlertState, T>): T {
        val (next, result) = change(get(server))
        set(server, next)
        return result
    }

    /** Called when an app is stopped or restarted from Hearth, so the next check doesn't report it. */
    fun expectStop(server: String, appId: String, nowMs: Long = System.currentTimeMillis()) =
        update(server) { it.copy(expectedStops = it.expectedStops + (appId to nowMs)) to Unit }

    /** Forgets [server], e.g. when it's signed out. */
    @Synchronized
    fun clear(server: String) {
        if (server !in states) return
        states = states - server
        persist()
    }

    private fun persist() {
        storage.saved = json.encodeToString(map, states)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val map = MapSerializer(String.serializer(), ServerAlertState.serializer())
    }
}
