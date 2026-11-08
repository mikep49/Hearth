package dev.hearth.casaos

import dev.hearth.casaos.data.originOf
import dev.hearth.casaos.data.profileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLoginsTest {
    @Test
    fun originKeepsSchemeHostAndPortOnly() {
        assertEquals("http://192.168.1.20:8282", originOf("http://192.168.1.20:8282/subscriptions.php?x=1#top"))
        assertEquals("http://casaos.local:8282", originOf("http://user:pw@casaos.local:8282/"))
        assertEquals("https://wallos.example.com", originOf("https://wallos.example.com/login"))
        assertEquals("http://[fd00::5]:8080", originOf("http://[fd00::5]:8080/a"))
    }

    @Test
    fun originRejectsNonWebAddresses() {
        assertNull(originOf("mailto:a@b.c"))
        assertNull(originOf("not a url"))
    }

    @Test
    fun appsOnOtherPortsGetTheirOwnProfile() {
        val a = profileName("http://192.168.1.20:8282")
        val b = profileName("http://192.168.1.20:8096")
        assertNotEquals(a, b)
        assertEquals(a, profileName("http://192.168.1.20:8282"))
        assertTrue(a.matches(Regex("app-[0-9a-f]{24}")))
    }
}
