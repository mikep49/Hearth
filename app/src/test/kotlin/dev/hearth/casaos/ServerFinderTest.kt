package dev.hearth.casaos

import dev.hearth.casaos.data.CasaProbe
import dev.hearth.casaos.data.FoundServer
import dev.hearth.casaos.data.looksLikeCasaPage
import dev.hearth.casaos.data.looksLikeCasaStatus
import dev.hearth.casaos.data.mergeFound
import dev.hearth.casaos.data.subnetHosts
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class ServerFinderTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test fun `a 24 subnet lists every other address`() {
        val hosts = subnetHosts(ip("192.168.1.37"), 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.37" in hosts)
    }

    @Test fun `a bigger subnet is cut down to the phone's 24`() {
        val hosts = subnetHosts(ip("10.0.5.9"), 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.0.5.") })
    }

    @Test fun `a small subnet stays small`() {
        assertEquals(listOf("192.168.1.65", "192.168.1.66"), subnetHosts(ip("192.168.1.67"), 29).take(2))
        assertEquals(5, subnetHosts(ip("192.168.1.67"), 29).size)
    }

    @Test fun `recognises the CasaOS status reply and page`() {
        assertTrue(looksLikeCasaStatus("""{"success":200,"message":"ok","data":{"initialized":true,"key":"x"}}"""))
        assertFalse(looksLikeCasaStatus("""{"success":200,"data":{}}"""))
        assertFalse(looksLikeCasaStatus("<html>router</html>"))
        assertFalse(looksLikeCasaStatus(null))
        assertTrue(looksLikeCasaPage("<!DOCTYPE html><html><head><title>CasaOS</title></head></html>"))
        assertFalse(looksLikeCasaPage("<html><head><title>Router login</title></head></html>"))
    }

    @Test fun `merging keeps one entry per machine and prefers its name`() {
        var list = mergeFound(emptyList(), FoundServer("192.168.1.20", "192.168.1.20"))
        list = mergeFound(list, FoundServer("192.168.1.5", "192.168.1.5"))
        assertEquals(listOf("192.168.1.5", "192.168.1.20"), list.map { it.address })

        list = mergeFound(list, FoundServer("casaos.local", "192.168.1.20"))
        assertEquals(listOf("casaos.local", "192.168.1.5"), list.map { it.address })

        // The bare address showing up after the name doesn't replace it.
        assertEquals(list, mergeFound(list, FoundServer("192.168.1.20", "192.168.1.20")))
    }

    private fun serve(handler: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
        start()
    }

    @Test fun `probe accepts a CasaOS server`() = runTest {
        val server = serve { r ->
            if (r.path == "/v1/users/status") MockResponse().setBody("""{"success":200,"data":{"initialized":false}}""")
            else MockResponse().setResponseCode(404)
        }
        try {
            assertTrue(CasaProbe(OkHttpClient()).isCasaOS(server.url("").toString().trimEnd('/')))
        } finally { server.shutdown() }
    }

    @Test fun `probe falls back to the dashboard page`() = runTest {
        val server = serve { r ->
            if (r.path == "/") MockResponse().setBody("<html><head><title>CasaOS</title></head></html>")
            else MockResponse().setResponseCode(404)
        }
        try {
            assertTrue(CasaProbe(OkHttpClient()).isCasaOS(server.url("").toString().trimEnd('/')))
        } finally { server.shutdown() }
    }

    @Test fun `probe rejects other web servers and closed ports`() = runTest {
        val server = serve { MockResponse().setBody("<html><head><title>Router</title></head></html>") }
        val base = server.url("").toString().trimEnd('/')
        try {
            assertFalse(CasaProbe(OkHttpClient()).isCasaOS(base))
        } finally { server.shutdown() }
        assertFalse(CasaProbe(OkHttpClient()).isCasaOS(base)) // nothing listening any more
    }
}
