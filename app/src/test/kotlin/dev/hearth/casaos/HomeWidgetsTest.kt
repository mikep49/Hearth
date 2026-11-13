package dev.hearth.casaos

import dev.hearth.casaos.data.DiskStats
import dev.hearth.casaos.data.HomeLayout
import dev.hearth.casaos.data.HomeLayouts
import dev.hearth.casaos.data.HomeSection
import dev.hearth.casaos.data.HomeSize.Large
import dev.hearth.casaos.data.HomeSize.Medium
import dev.hearth.casaos.data.HomeSize.Small
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
import dev.hearth.casaos.data.homeSections
import dev.hearth.casaos.data.homeSizes
import dev.hearth.casaos.data.homeWidgets
import dev.hearth.casaos.data.moved
import dev.hearth.casaos.data.savedHomeSizes
import dev.hearth.casaos.data.shifted
import dev.hearth.casaos.data.tileRows
import dev.hearth.casaos.ui.dashboard.hasReading
import dev.hearth.casaos.ui.settings.homeWidgetsSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWidgetsTest {
    @Test fun `home starts with every widget, small ones first`() {
        assertEquals(HomeWidget.entries, homeWidgets(null, null))
        assertEquals(
            listOf(Small, Small, Small, Small, Medium, Medium, Medium, Medium),
            HomeWidget.Default.map { HomeLayout.Default.sizeOf(it) },
        )
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

    @Test fun `every widget comes in a size it has, smallest first`() {
        HomeWidget.entries.forEach { widget ->
            assertTrue(widget.name, widget.defaultSize in widget.sizes)
            assertEquals(widget.name, widget.sizes.sortedBy { it.ordinal }.distinct(), widget.sizes)
        }
        assertFalse("a list of drives has one size", Drives.resizable)
        assertTrue(ProcessorGraph.resizable)
    }

    @Test fun `a layout saved before sizes existed keeps each widget's own size`() {
        assertEquals(emptyMap<HomeWidget, Any>(), homeSizes(null))
        val layout = HomeLayout(homeWidgets("Network,CpuTile", ""), homeSizes(null))
        assertEquals(Medium, layout.sizeOf(Network))
        assertEquals(Small, layout.sizeOf(CpuTile))
    }

    @Test fun `saved sizes are read back`() {
        val sizes = mapOf(ProcessorGraph to Large, Network to Small, CpuTile to Medium)
        assertEquals(sizes, homeSizes(savedHomeSizes(sizes)))
        assertEquals("ProcessorGraph:Large,Network:Small,CpuTile:Medium", savedHomeSizes(sizes))
        assertEquals("", savedHomeSizes(emptyMap()))
    }

    @Test fun `sizes that make no sense are dropped`() {
        assertEquals(
            mapOf(Network to Small),
            homeSizes("Gone:Small,Network:Small,Drives:Small,CpuTile:Huge,MemoryTile,ProcessorGraph:Medium,,:"),
        )
        assertEquals("a size the widget doesn't come in", Medium, HomeLayout(listOf(Drives), mapOf(Drives to Large)).sizeOf(Drives))
    }

    @Test fun `resizing a widget changes its size and nothing else`() {
        val home = HomeLayout.Default.resized(ProcessorGraph, Large)
        assertEquals(Large, home.sizeOf(ProcessorGraph))
        assertEquals(HomeWidget.Default, home.widgets)
        assertEquals(Medium, home.sizeOf(Network))
        assertEquals("a size it doesn't come in", home, home.resized(Drives, Small))
        assertEquals("back to the size it comes in is back to the start", HomeLayout.Default, home.resized(ProcessorGraph, Medium))
    }

    @Test fun `changing a size steps up through the sizes and round again`() {
        var home = HomeLayout.Default
        val seen = List(4) { home.sizeOf(ProcessorGraph).also { home = home.resized(ProcessorGraph, home.nextSize(ProcessorGraph)) } }
        assertEquals(listOf(Medium, Large, Small, Medium), seen)
        assertEquals(Medium, HomeLayout.Default.nextSize(CpuTile))
        assertEquals(Small, HomeLayout.Default.resized(CpuTile, Medium).nextSize(CpuTile))
        assertEquals("only one size", Medium, HomeLayout.Default.nextSize(Drives))
    }

    @Test fun `a removed widget comes back at the size it had`() {
        val home = HomeLayout.Default.resized(Network, Large).removing(Network)
        assertFalse(Network in home.widgets)
        assertEquals(Large, home.adding(Network).sizeOf(Network))
    }

    @Test fun `servers share a layout until one is given its own`() {
        val shared = HomeLayouts(shared = HomeLayout(listOf(CpuTile, Network), mapOf(Network to Large)))
        assertEquals(shared.shared, shared.of("a"))
        assertFalse(shared.isOwn("a"))

        // Its own layout starts as the shared one, sizes too, so turning the switch on moves nothing.
        val split = shared.owning("a", true)
        assertTrue(split.isOwn("a"))
        assertEquals(shared.shared, split.of("a"))
        assertEquals("already has its own", split, split.owning("a", true))
        assertEquals("never had its own", shared, shared.owning("a", false))
    }

    @Test fun `changing a server's own layout leaves the others alone`() {
        val before = HomeLayout(listOf(CpuTile, Network))
        val layouts = HomeLayouts(shared = before).owning("a", true)
            .with("a", HomeLayout(listOf(Drives, Drives, ProcessorGraph), mapOf(ProcessorGraph to Large)))
        assertEquals("repeats are dropped", listOf(Drives, ProcessorGraph), layouts.of("a").widgets)
        assertEquals(Large, layouts.of("a").sizeOf(ProcessorGraph))
        assertEquals(before, layouts.of("b"))

        // A server without its own layout changes the shared one, for every server that shows it.
        val changed = layouts.with("b", before.resized(Network, Small))
        assertEquals(Small, changed.of("b").sizeOf(Network))
        assertEquals(Small, changed.of("c").sizeOf(Network))
        assertEquals(layouts.of("a"), changed.of("a"))
    }

    @Test fun `a server that gives up its own layout shows the shared one again`() {
        val layouts = HomeLayouts(shared = HomeLayout(listOf(CpuTile))).owning("a", true).with("a", HomeLayout(listOf(Drives))).owning("b", true)
        val back = layouts.owning("a", false)
        assertEquals(listOf(CpuTile), back.of("a").widgets)
        assertEquals(setOf("b"), back.own.keys)
    }

    @Test fun `dragging a widget onto another takes its place`() {
        val home = listOf(CpuTile, MemoryTile, Network, Drives)
        assertEquals(listOf(MemoryTile, Network, CpuTile, Drives), home.moved(CpuTile, Network))
        assertEquals(listOf(Drives, CpuTile, MemoryTile, Network), home.moved(Drives, CpuTile))
        assertEquals(home, home.moved(CpuTile, CpuTile))
        assertEquals("a widget that isn't on Home", home, home.moved(StorageTile, CpuTile))
        val sized = HomeLayout(home, mapOf(Network to Small))
        assertEquals("sizes go with the widgets", HomeLayout(home.moved(CpuTile, Network), mapOf(Network to Small)), sized.moved(CpuTile, Network))
    }

    @Test fun `a widget moves a place at a time without dragging`() {
        val home = listOf(CpuTile, MemoryTile, Network)
        assertEquals(listOf(MemoryTile, CpuTile, Network), home.shifted(CpuTile, 1))
        assertEquals(listOf(CpuTile, Network, MemoryTile), home.shifted(Network, -1))
        assertEquals("already first", home, home.shifted(CpuTile, -1))
        assertEquals("already last", home, home.shifted(Network, 1))
        assertEquals(listOf(MemoryTile, CpuTile, Network), HomeLayout(home).shifted(CpuTile, 1).widgets)
    }

    @Test fun `an added small widget joins the small ones and any other goes last`() {
        val home = HomeLayout(listOf(CpuTile, MemoryTile, Network))
        assertEquals(listOf(CpuTile, MemoryTile, StorageTile, Network), home.adding(StorageTile).widgets)
        assertEquals(listOf(CpuTile, MemoryTile, Network, Drives), home.adding(Drives).widgets)
        assertEquals("no small ones to join", listOf(Network, CpuTile), HomeLayout(listOf(Network)).adding(CpuTile).widgets)
        assertEquals("already there", home, home.adding(CpuTile))
        assertEquals(
            "a tile made medium goes last, like a card",
            listOf(CpuTile, MemoryTile, Network, StorageTile),
            home.resized(StorageTile, Medium).adding(StorageTile).widgets,
        )
        assertEquals(
            "a card made small joins the small ones",
            listOf(CpuTile, MemoryTile, ProcessorGraph, Network),
            home.resized(ProcessorGraph, Small).adding(ProcessorGraph).widgets,
        )
    }

    @Test fun `neighbouring small widgets are laid out together`() {
        assertEquals(
            listOf(
                HomeSection(tiles = true, listOf(CpuTile, MemoryTile)),
                HomeSection(tiles = false, listOf(Network)),
                HomeSection(tiles = true, listOf(StorageTile)),
                HomeSection(tiles = false, listOf(Drives, MemoryBar)),
            ),
            homeSections(HomeLayout(listOf(CpuTile, MemoryTile, Network, StorageTile, Drives, MemoryBar))),
        )
        assertEquals(emptyList<HomeSection>(), homeSections(HomeLayout(emptyList())))
    }

    @Test fun `resizing a widget moves it between the tiles and the cards`() {
        val home = HomeLayout(listOf(CpuTile, MemoryTile, Network, ProcessorGraph))
        assertEquals(
            listOf(HomeSection(tiles = true, listOf(CpuTile, MemoryTile, Network)), HomeSection(tiles = false, listOf(ProcessorGraph))),
            homeSections(home.resized(Network, Small)),
        )
        assertEquals(
            listOf(HomeSection(tiles = true, listOf(CpuTile)), HomeSection(tiles = false, listOf(MemoryTile, Network, ProcessorGraph))),
            homeSections(home.resized(MemoryTile, Medium)),
        )
    }

    @Test fun `a small widget with a row to itself is shown at medium size`() {
        assertEquals(
            listOf(listOf(CpuTile, TemperatureTile) to Small, listOf(MemoryTile, StorageTile) to Small),
            tileRows(listOf(CpuTile, TemperatureTile, MemoryTile, StorageTile), columns = 2),
        )
        assertEquals(
            listOf(listOf(CpuTile, TemperatureTile) to Small, listOf(MemoryTile) to Medium),
            tileRows(listOf(CpuTile, TemperatureTile, MemoryTile), columns = 2),
        )
        assertEquals(
            "on a wide screen, tiles short of a full row share it",
            listOf(listOf(CpuTile, TemperatureTile, MemoryTile) to Small),
            tileRows(listOf(CpuTile, TemperatureTile, MemoryTile), columns = 4),
        )
        assertEquals(listOf(listOf(Network) to Medium), tileRows(listOf(Network), columns = 4))
        assertEquals(emptyList<Any>(), tileRows(emptyList(), columns = 2))
    }

    @Test fun `a widget is left out where the server has no reading for it`() {
        val bare = SystemStats(
            cpuPercent = 1.0, cpuCores = 4, cpuTempC = null, cpuModel = "", memTotal = 1, memUsed = 1, memPercent = 100.0,
            disk = null, netRxBytes = 0, netTxBytes = 0, sampledAtMs = 0,
        )
        assertEquals(
            listOf(CpuTile, MemoryTile, ProcessorGraph, Network, MemoryBar),
            HomeLayout.Default.only { it.hasReading(bare, emptyList()) }.widgets,
        )
        val full = bare.copy(cpuTempC = 40, disk = DiskStats(totalBytes = 10, usedBytes = 5))
        assertEquals("the Drives widget falls back to the system disk's total", HomeLayout.Default, HomeLayout.Default.only { it.hasReading(full, emptyList()) })
    }

    @Test fun `the settings row says how much is on Home`() {
        assertEquals("All 8 widgets", homeWidgetsSummary(HomeWidget.Default))
        assertEquals("3 of 8 widgets", homeWidgetsSummary(listOf(CpuTile, Network, Drives)))
        assertEquals("Nothing on Home", homeWidgetsSummary(emptyList()))
        assertEquals("3 of 8 widgets · this server only", homeWidgetsSummary(listOf(CpuTile, Network, Drives), own = true))
    }
}
