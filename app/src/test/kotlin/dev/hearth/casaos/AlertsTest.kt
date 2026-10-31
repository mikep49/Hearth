package dev.hearth.casaos

import dev.hearth.casaos.data.Alert
import dev.hearth.casaos.data.AlertReading
import dev.hearth.casaos.data.AlertSettings
import dev.hearth.casaos.data.AlertStorage
import dev.hearth.casaos.data.AlertStore
import dev.hearth.casaos.data.AppState
import dev.hearth.casaos.data.EXPECTED_STOP_MS
import dev.hearth.casaos.data.InstalledApp
import dev.hearth.casaos.data.ServerAlertState
import dev.hearth.casaos.data.TOTAL_STORAGE
import dev.hearth.casaos.data.evaluateAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    private val on = AlertSettings(enabled = true)
    private var now = 1_000_000_000L

    private fun app(id: String, state: AppState, update: Boolean = false) = InstalledApp(
        id = id, name = id.replaceFirstChar { it.uppercase() }, tagline = null, category = null, iconUrl = null,
        state = state, rawStatus = state.name.lowercase(), updateAvailable = update,
        webPort = null, webPath = "/", scheme = "http",
    )

    private fun online(vararg apps: InstalledApp, volumes: Map<String, Double> = mapOf(TOTAL_STORAGE to 50.0)) =
        AlertReading.Online(apps.toList(), volumes)

    /** Runs the readings in order, 15 minutes apart, and returns the alerts from each one. */
    private fun run(
        vararg readings: AlertReading,
        settings: AlertSettings = on,
        start: ServerAlertState = ServerAlertState(),
    ): Pair<ServerAlertState, List<List<Alert>>> {
        var state = start
        val out = readings.map { r ->
            now += 15 * 60_000
            val (next, alerts) = evaluateAlerts(state, r, settings, now)
            state = next
            alerts
        }
        return state to out
    }

    @Test fun `offline alerts once after two failed checks, then says when it's back`() {
        val (_, alerts) = run(AlertReading.Offline, AlertReading.Offline, AlertReading.Offline, online())
        assertEquals(listOf(emptyList(), listOf(Alert.ServerOffline), emptyList(), listOf(Alert.BackOnline)), alerts)
    }

    @Test fun `one failed check doesn't alert`() {
        val (_, alerts) = run(AlertReading.Offline, online(), AlertReading.Offline, online())
        assertTrue(alerts.all { it.isEmpty() })
    }

    @Test fun `storage alerts once when it passes 90 percent, and again only after dropping below 85`() {
        fun disk(p: Double) = online(volumes = mapOf(TOTAL_STORAGE to p))
        val (_, alerts) = run(disk(80.0), disk(91.0), disk(95.0), disk(88.0), disk(92.0), disk(70.0), disk(90.0))
        assertEquals(
            listOf(
                emptyList(),
                listOf(Alert.StorageFull(TOTAL_STORAGE, 91.0)),
                emptyList(), emptyList(), emptyList(), emptyList(),
                listOf(Alert.StorageFull(TOTAL_STORAGE, 90.0)),
            ),
            alerts,
        )
    }

    @Test fun `a full volume stays reported while drives aren't read`() {
        val withDrive = online(volumes = mapOf(TOTAL_STORAGE to 50.0, "/media/Backup" to 96.0))
        val (_, alerts) = run(withDrive, online(), withDrive)
        assertEquals(listOf(listOf(Alert.StorageFull("/media/Backup", 96.0)), emptyList(), emptyList()), alerts)
    }

    @Test fun `an app that stops by itself alerts once, until it runs again`() {
        val (_, alerts) = run(
            online(app("jellyfin", AppState.Running)),
            online(app("jellyfin", AppState.Stopped)),
            online(app("jellyfin", AppState.Stopped)),
            online(app("jellyfin", AppState.Running)),
            online(app("jellyfin", AppState.Stopped)),
        )
        val stopped = Alert.AppStopped("jellyfin", "Jellyfin", restarting = false)
        assertEquals(listOf(emptyList(), listOf(stopped), emptyList(), emptyList(), listOf(stopped)), alerts)
    }

    @Test fun `apps already stopped at the first check aren't reported`() {
        val (_, alerts) = run(online(app("a", AppState.Stopped)), online(app("a", AppState.Stopped)))
        assertTrue(alerts.all { it.isEmpty() })
    }

    @Test fun `an app stuck restarting is reported once`() {
        val (_, alerts) = run(
            online(app("db", AppState.Running)),
            online(app("db", AppState.Restarting)),
            online(app("db", AppState.Restarting)),
            online(app("db", AppState.Restarting)),
        )
        assertEquals(listOf(emptyList(), emptyList(), listOf(Alert.AppStopped("db", "Db", restarting = true)), emptyList()), alerts)
    }

    @Test fun `stopping an app from Hearth isn't reported`() {
        val (first, _) = run(online(app("a", AppState.Running)))
        val expecting = first.copy(expectedStops = mapOf("a" to now))
        val (state, alerts) = run(online(app("a", AppState.Stopped)), start = expecting)
        assertTrue(alerts.single().isEmpty())
        assertTrue(state.expectedStops.isEmpty())
    }

    @Test fun `a stop from Hearth stops being expected after an hour`() {
        val (first, _) = run(online(app("a", AppState.Running)))
        val expecting = first.copy(expectedStops = mapOf("a" to now - EXPECTED_STOP_MS))
        val (_, alerts) = run(online(app("a", AppState.Stopped)), start = expecting)
        assertEquals(listOf(Alert.AppStopped("a", "A", restarting = false)), alerts.single())
    }

    @Test fun `updates are reported once each, grouped`() {
        val (_, alerts) = run(
            online(app("a", AppState.Running, update = true), app("b", AppState.Running, update = true)),
            online(app("a", AppState.Running, update = true), app("b", AppState.Running, update = true)),
            online(app("a", AppState.Running, update = true), app("b", AppState.Running), app("c", AppState.Running, update = true)),
            online(app("a", AppState.Running), app("b", AppState.Running), app("c", AppState.Running, update = true)),
        )
        assertEquals(
            listOf(listOf(Alert.UpdatesAvailable(listOf("A", "B"))), emptyList(), listOf(Alert.UpdatesAvailable(listOf("C"))), emptyList()),
            alerts,
        )
    }

    @Test fun `alerts that are off stay quiet, and turning one on later doesn't report old news`() {
        val quiet = on.copy(appStopped = false, updates = false)
        val (state, alerts) = run(
            online(app("a", AppState.Running, update = true)),
            online(app("a", AppState.Stopped, update = true)),
            settings = quiet,
        )
        assertTrue(alerts.all { it.isEmpty() })
        val (_, later) = run(online(app("a", AppState.Stopped, update = true)), start = state)
        assertTrue(later.single().isEmpty())
    }

    @Test fun `alert state is saved, and a stop from Hearth isn't lost`() {
        val storage = object : AlertStorage { override var saved: String? = null }
        val store = AlertStore(storage)
        store.set("s1", ServerAlertState(failures = 1, apps = mapOf("a" to AppState.Running)))
        store.expectStop("s1", "a", nowMs = 42)

        val reloaded = AlertStore(storage).get("s1")
        assertEquals(1, reloaded.failures)
        assertEquals(AppState.Running, reloaded.apps["a"])
        assertEquals(42L, reloaded.expectedStops["a"])

        store.clear("s1")
        assertFalse(AlertStore(storage).get("s1").apps.isNotEmpty())
    }
}
