package dev.hearth.casaos

import dev.hearth.casaos.data.SystemStats
import dev.hearth.casaos.ui.dashboard.DashboardState
import dev.hearth.casaos.ui.dashboard.advance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardStateTest {
    private fun sample(atMs: Long, rx: Long, tx: Long, cpu: Double = 50.0) = SystemStats(
        cpuPercent = cpu, cpuCores = 4, cpuTempC = null, cpuModel = "", memTotal = 1, memUsed = 1, memPercent = 100.0,
        disk = null, netRxBytes = rx, netTxBytes = tx, sampledAtMs = atMs,
    )

    @Test fun `first sample has no rate`() {
        val s = DashboardState().advance(sample(0, 1_000, 2_000))
        assertEquals(0L, s.rxRate)
        assertEquals(listOf(0.5f), s.cpuHistory)
    }

    @Test fun `rate is bytes per second between samples`() {
        val s = DashboardState().advance(sample(0, 0, 0)).advance(sample(2_000, 4_000, 1_000))
        assertEquals(2_000L, s.rxRate)
        assertEquals(500L, s.txRate)
    }

    @Test fun `counter reset never produces a negative rate`() {
        val s = DashboardState().advance(sample(0, 9_000, 9_000)).advance(sample(1_000, 100, 100))
        assertEquals(0L, s.rxRate)
        assertEquals(0L, s.txRate)
    }

    @Test fun `history is capped and a good sample clears the error`() {
        var s = DashboardState(error = "boom")
        repeat(60) { s = s.advance(sample(it * 1_000L, it * 10L, 0)) }
        assertEquals(40, s.cpuHistory.size)
        assertNull(s.error)
    }
}
