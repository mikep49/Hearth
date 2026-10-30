package dev.hearth.casaos

import dev.hearth.casaos.data.AppState
import dev.hearth.casaos.data.InstalledApp
import dev.hearth.casaos.ui.apps.matches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchTest {
    private val jellyfin = InstalledApp(
        id = "jellyfin",
        name = "Jellyfin",
        tagline = "Free media server",
        category = "Media",
        iconUrl = null,
        state = AppState.Running,
        rawStatus = "running",
        updateAvailable = false,
        webPort = "8096",
        webPath = "/",
        scheme = "http",
        image = "linuxserver/jellyfin:10.9.1",
    )

    @Test fun `matches name, tagline and image, ignoring case`() {
        assertTrue(jellyfin.matches("JELLY"))
        assertTrue(jellyfin.matches("media server"))
        assertTrue(jellyfin.matches("linuxserver"))
    }

    @Test fun `does not match unrelated text`() {
        assertFalse(jellyfin.matches("plex"))
    }
}
