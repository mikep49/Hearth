package dev.hearth.casaos

import dev.hearth.casaos.data.DiskStats
import dev.hearth.casaos.data.HomeLayouts
import dev.hearth.casaos.data.HomeSection
import dev.hearth.casaos.data.HomeWidget
import dev.hearth.casaos.data.HomeWidget.CpuTile
import dev.hearth.casaos.data.HomeWidget.Drives
import dev.hearth.casaos.data.HomeWidget.MemoryBar
import dev.hearth.casaos.data.HomeWidget.MemoryTile
import dev.hearth.casaos.data.HomeWidget.Network
import dev.hearth.casaos.data.HomeWidget.ProcessorGraph
import dev.hearth.casaos.data.HomeWidget.StorageTile
import dev.hearth.casaos.data.HomeWidget.TemperatureTile
import dev.hearth.casaos.data.SystemStats
import dev.hearth.casaos.data.adding
import dev.hearth.casaos.data.homeSections
import dev.hearth.casaos.data.homeWidgets
import dev.hearth.casaos.data.loneTiles
import dev.hearth.casaos.data.moved
import dev.hearth.casaos.data.shifted
import dev.hearth.casaos.ui.dashboard.hasReading
import dev.hearth.casaos.ui.settings.homeWidgetsSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeWidgetsTest {
    @Test fun `home starts with every widget, tiles first`() {
        assertEquals(HomeWidget.entries, homeWidgets(null, null))
        assertEquals(listOf(true, true, true, true, false, false, false, false), HomeWidget.Default.map { it.tile })
    }

    @Test fun `a saved layout keeps its order and what was removed`() {
        assertEquals(
            listOf(Network, CpuTile, MemoryTile),
            homeWidgets("Network,CpuTile,MemoryTile", "TemperatureTile,StorageTile,ProcessorGraph,MemoryBar,Drives"),
        )
        assertEquals("everything removed", emptyList<HomeWidget>(), homeWidgets("", HomeWidget.entries.joinToString(",") { it.name }))
    }

    @Test fun `a widget added in a later version shows up at the end`() {
        // Saved before Drives and MemoryBar existed: neither shown nor removed.
        assertEquals(
            listOf(Network, CpuTile, MemoryBar, Drives),
            homeWidgets("Network,CpuTile", "TemperatureTile,MemoryTile,StorageTile,ProcessorGraph"),
        )
    }

    @Test fun `names that no longer exist and repeats are dropped`() {
        assertEquals(
            listOf(CpuTile, Network),
            homeWidgets("CpuTile,Gone,CpuTile,Network", "TemperatureTile,MemoryTile,StorageTile,ProcessorGraph,MemoryBar,Drives,Gone"),
        )
    }

    @Test fun `servers share a layout until one is given its own`() {
        val shared = HomeLayouts(shared = listOf(CpuTile, Network))
        assertEquals(listOf(CpuTile, Network), shared.of("a"))
        assertEquals(false, shared.isOwn("a"))

        // Its own layout starts as the shared one, so turning the switch on moves nothing.
        val split = shared.owning("a", true)
        assertEquals(true, split.isOwn("a"))
        assertEquals(listOf(CpuTile, Network), split.of("a"))
        assertEquals("already has its own", split, split.owning("a", true))
        assertEquals("never had its own", shared, shared.owning("a", false))
    }

    @Test fun `changing a server's own layout leaves the others alone`() {
        val layouts = HomeLayouts(shared = listOf(CpuTile, Network)).owning("a", true).with("a", listOf(Drives, Drives, MemoryTile))
        assertEquals("repeats are dropped", listOf(Drives, MemoryTile), layouts.of("a"))
        assertEquals(listOf(CpuTile, Network), layouts.of("b"))

        // A server without its own layout changes the shared one, for every server that shows it.
        val changed = layouts.with("b", listOf(Network))
        assertEquals(listOf(Network), changed.of("b"))
        assertEquals(listOf(Network), changed.of("c"))
        assertEquals(listOf(Drives, MemoryTile), changed.of("a"))
    }

    @Test fun `a server that gives up its own layout shows the shared one again`() {
        val layouts = HomeLayouts(shared = listOf(CpuTile)).owning("a", true).with("a", listOf(Drives)).owning("b", true)
        val back = layouts.owning("a", false)
        assertEquals(listOf(CpuTile), back.of("a"))
        assertEquals(setOf("b"), back.own.keys)
    }

    @Test fun `dragging a widget onto another takes its place`() {
        val home = listOf(CpuTile, MemoryTile, Network, Drives)
        assertEquals(listOf(MemoryTile, Network, CpuTile, Drives), home.moved(CpuTile, Network))
        assertEquals(listOf(Drives, CpuTile, MemoryTile, Network), home.moved(Drives, CpuTile))
        assertEquals(home, home.moved(CpuTile, CpuTile))
        assertEquals("a widget that isn't on Home", home, home.moved(StorageTile, CpuTile))
    }

    @Test fun `a widget moves a place at a time without dragging`() {
        val home = listOf(CpuTile, MemoryTile, Network)
        assertEquals(listOf(MemoryTile, CpuTile, Network), home.shifted(CpuTile, 1))
        assertEquals(listOf(CpuTile, Network, MemoryTile), home.shifted(Network, -1))
        assertEquals("already first", home, home.shifted(CpuTile, -1))
        assertEquals("already last", home, home.shifted(Network, 1))
    }

    @Test fun `an added tile joins the tiles and an added card goes last`() {
        val home = listOf(CpuTile, MemoryTile, Network)
        assertEquals(listOf(CpuTile, MemoryTile, StorageTile, Network), home.adding(StorageTile))
        assertEquals(listOf(CpuTile, MemoryTile, Network, Drives), home.adding(Drives))
        assertEquals("no tiles to join", listOf(Network, CpuTile), listOf(Network).adding(CpuTile))
        assertEquals("already there", home, home.adding(CpuTile))
    }

    @Test fun `neighbouring tiles are laid out together`() {
        assertEquals(
            listOf(
                HomeSection(tiles = true, listOf(CpuTile, MemoryTile)),
                HomeSection(tiles = false, listOf(Network)),
                HomeSection(tiles = true, listOf(StorageTile)),
                HomeSection(tiles = false, listOf(Drives, MemoryBar)),
            ),
            homeSections(listOf(CpuTile, MemoryTile, Network, StorageTile, Drives, MemoryBar)),
        )
        assertEquals(emptyList<HomeSection>(), homeSections(emptyList()))
    }

    @Test fun `the odd tile at the end of a run has its row to itself`() {
        assertEquals(emptySet<HomeWidget>(), loneTiles(HomeWidget.Default))
        assertEquals(setOf(MemoryTile), loneTiles(listOf(CpuTile, TemperatureTile, MemoryTile, Network)))
        assertEquals(setOf(CpuTile, StorageTile), loneTiles(listOf(CpuTile, Network, MemoryTile, TemperatureTile, StorageTile)))
    }

    @Test fun `a widget is left out where the server has no reading for it`() {
        val bare = SystemStats(
            cpuPercent = 1.0, cpuCores = 4, cpuTempC = null, cpuModel = "", memTotal = 1, memUsed = 1, memPercent = 100.0,
            disk = null, netRxBytes = 0, netTxBytes = 0, sampledAtMs = 0,
        )
        assertEquals(
            listOf(CpuTile, MemoryTile, ProcessorGraph, Network, MemoryBar),
            HomeWidget.Default.filter { it.hasReading(bare, emptyList()) },
        )
        val full = bare.copy(cpuTempC = 40, disk = DiskStats(totalBytes = 10, usedBytes = 5))
        assertEquals("the Drives widget falls back to the system disk's total", HomeWidget.Default, HomeWidget.Default.filter { it.hasReading(full, emptyList()) })
    }

    @Test fun `the settings row says how much is on Home`() {
        assertEquals("All 8 widgets", homeWidgetsSummary(HomeWidget.Default))
        assertEquals("3 of 8 widgets", homeWidgetsSummary(listOf(CpuTile, Network, Drives)))
        assertEquals("Nothing on Home", homeWidgetsSummary(emptyList()))
        assertEquals("3 of 8 widgets · this server only", homeWidgetsSummary(listOf(CpuTile, Network, Drives), own = true))
    }
}
