package dev.hearth.casaos

import dev.hearth.casaos.data.SystemStats
import dev.hearth.casaos.ui.dashboard.DashboardState
import dev.hearth.casaos.ui.dashboard.advance
import dev.hearth.casaos.ui.dashboard.chartScale
import dev.hearth.casaos.ui.dashboard.formatCpuTotal
import dev.hearth.casaos.ui.dashboard.halfScaleLabel
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
        repeat(150) { s = s.advance(sample(it * 1_000L, it * 10L, 0)) }
        assertEquals("five minutes of processor readings, for the Task manager", 120, s.cpuHistory.size)
        assertEquals("the network graphs only show the last two", 48, s.rxHistory.size)
        assertNull(s.error)
    }

    @Test fun `the graph zooms in on a quiet server and back out for a busy one`() {
        assertEquals("nothing read yet", 5, chartScale(emptyList()))
        assertEquals(5, chartScale(listOf(0.01f, 0.012f, 0.008f)))
        assertEquals("the highest reading sets the scale, not the latest", 20, chartScale(listOf(0.01f, 0.12f, 0.01f)))
        assertEquals("a reading close to the top moves up a step to leave room", 10, chartScale(listOf(0.048f)))
        assertEquals(50, chartScale(listOf(0.3f)))
        assertEquals(100, chartScale(listOf(0.46f)))
        assertEquals(100, chartScale(listOf(1f)))
        assertEquals("more than everything still fits", 100, chartScale(listOf(1.2f)))
    }

    @Test fun `the middle line is labelled with half the scale`() {
        assertEquals("2.5%", halfScaleLabel(5))
        assertEquals("5%", halfScaleLabel(10))
        assertEquals("50%", halfScaleLabel(100))
    }

    @Test fun `low processor load keeps a decimal`() {
        assertEquals("0%", formatCpuTotal(0.0))
        assertEquals("0.4%", formatCpuTotal(0.4))
        assertEquals("1%", formatCpuTotal(1.0))
        assertEquals("1.2%", formatCpuTotal(1.23))
        assertEquals("10%", formatCpuTotal(9.96))
        assertEquals("13%", formatCpuTotal(12.5))
        assertEquals("100%", formatCpuTotal(100.0))
    }
}
