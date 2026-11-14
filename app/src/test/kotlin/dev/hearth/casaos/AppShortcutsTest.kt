package dev.hearth.casaos

import dev.hearth.casaos.data.AppState
import dev.hearth.casaos.data.InstalledApp
import dev.hearth.casaos.data.savedShortcuts
import dev.hearth.casaos.data.shortcutApps
import dev.hearth.casaos.data.shortcutSlots
import dev.hearth.casaos.data.shortcuts
import dev.hearth.casaos.data.toggledShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppShortcutsTest {
    private val server = "http://192.168.1.20"

    private fun app(id: String, port: String? = "8080", state: AppState = AppState.Running) =
        InstalledApp(id, id.replaceFirstChar { it.uppercase() }, null, null, null, state, state.name.lowercase(), false, port, "/", "http")

    private val jellyfin = app("jellyfin")
    private val immich = app("immich", state = AppState.Stopped)
    private val syncthing = app("syncthing")
    private val worker = app("worker", port = null) // no web page
    private val apps = listOf(jellyfin, worker, immich, syncthing)

    @Test fun `until any are chosen, every app with a web page shows, in the server's order`() {
        assertEquals(listOf(jellyfin, immich, syncthing), shortcutApps(apps, chosen = null, server))
        assertEquals(emptyList<InstalledApp>(), shortcutApps(listOf(worker), chosen = null, server))
    }

    @Test fun `chosen apps show in the order they were chosen`() {
        assertEquals(listOf(syncthing, jellyfin), shortcutApps(apps, listOf("syncthing", "jellyfin"), server))
        assertEquals("nothing chosen is nothing shown", emptyList<InstalledApp>(), shortcutApps(apps, emptyList(), server))
    }

    @Test fun `uninstalled apps and apps without a web page are left out`() {
        assertEquals(listOf(immich), shortcutApps(apps, listOf("gone", "worker", "immich"), server))
    }

    @Test fun `the first change starts from every app shown`() {
        val openable = shortcutApps(apps, null, server)
        assertEquals(listOf("jellyfin", "syncthing"), toggledShortcut(null, openable, "immich"))
    }

    @Test fun `ticking an app puts it last and unticking takes it off`() {
        val openable = shortcutApps(apps, null, server)
        assertEquals(listOf("syncthing", "jellyfin"), toggledShortcut(listOf("syncthing"), openable, "jellyfin"))
        assertEquals(listOf("syncthing"), toggledShortcut(listOf("syncthing", "jellyfin"), openable, "jellyfin"))
        // Unticked and ticked again, an app moves to the end.
        val moved = toggledShortcut(toggledShortcut(listOf("jellyfin", "immich", "syncthing"), openable, "jellyfin"), openable, "jellyfin")
        assertEquals(listOf("immich", "syncthing", "jellyfin"), moved)
    }

    @Test fun `chosen apps are saved and read back`() {
        assertNull("none chosen yet", shortcuts(null))
        assertEquals(listOf("syncthing", "jellyfin"), shortcuts(savedShortcuts(listOf("syncthing", "jellyfin"))))
        assertEquals("all taken off", emptyList<String>(), shortcuts(savedShortcuts(emptyList())))
        assertEquals("repeats and blanks are dropped", listOf("a", "b"), shortcuts("a,,b,a"))
    }

    @Test fun `apps that don't fit leave the last place to say how many more there are`() {
        assertEquals(3 to 0, shortcutSlots(3, 4))
        assertEquals(4 to 0, shortcutSlots(4, 4))
        assertEquals(3 to 2, shortcutSlots(5, 4))
        assertEquals(0 to 0, shortcutSlots(0, 4))
        assertEquals("as many as there are", 30 to 0, shortcutSlots(30, Int.MAX_VALUE))
        assertEquals("room for the button only", 0 to 2, shortcutSlots(2, 1))
    }
}
