package dev.hearth.casaos

import dev.hearth.casaos.data.SystemStats
import dev.hearth.casaos.ui.dashboard.DashboardState
import dev.hearth.casaos.ui.dashboard.Heat
import dev.hearth.casaos.ui.dashboard.advance
import dev.hearth.casaos.ui.dashboard.chartScale
import dev.hearth.casaos.ui.dashboard.cpuName
import dev.hearth.casaos.ui.dashboard.formatCpuTotal
import dev.hearth.casaos.ui.dashboard.greeting
import dev.hearth.casaos.ui.dashboard.halfScaleLabel
import dev.hearth.casaos.ui.dashboard.heat
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

    @Test fun `the greeting follows the time of day`() {
        assertEquals("Good evening", greeting(0))
        assertEquals("Good evening", greeting(4))
        assertEquals("Good morning", greeting(5))
        assertEquals("Good morning", greeting(11))
        assertEquals("Good afternoon", greeting(12))
        assertEquals("Good afternoon", greeting(17))
        assertEquals("Good evening", greeting(18))
        assertEquals("Good evening", greeting(23))
    }

    @Test fun `the temperature tile warms up with the processor`() {
        assertEquals(Heat.Cool, heat(38))
        assertEquals(Heat.Cool, heat(64))
        assertEquals(Heat.Warm, heat(65))
        assertEquals(Heat.Warm, heat(79))
        assertEquals(Heat.Hot, heat(80))
        assertEquals(Heat.Hot, heat(101))
    }

    @Test fun `the processor tile names the maker properly`() {
        assertEquals("Intel", cpuName("intel"))
        assertEquals("AMD", cpuName("amd"))
        assertEquals("ARM", cpuName("arm"))
        assertEquals("Intel Core i5-1235U", cpuName(" Intel Core i5-1235U "))
        assertEquals("", cpuName(""))
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
