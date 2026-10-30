package dev.hearth.casaos

import dev.hearth.casaos.data.AppAction
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.Credentials
import dev.hearth.casaos.data.TokenDto
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeCredentials(override var baseUrl: String? = null) : Credentials {
    override var accessToken: String? = null
    override var refreshToken: String? = null
    var signedOut = false
    override fun signIn(baseUrl: String, username: String, token: TokenDto) {
        this.baseUrl = baseUrl; accessToken = token.accessToken; refreshToken = token.refreshToken
    }
    override fun updateTokens(token: TokenDto) { accessToken = token.accessToken; refreshToken = token.refreshToken }
    override fun signOut() { signedOut = true; baseUrl = null; accessToken = null; refreshToken = null }
}

class CasaClientTest {
    private lateinit var server: MockWebServer
    private lateinit var creds: FakeCredentials
    private lateinit var client: CasaClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        creds = FakeCredentials()
        client = CasaClient(creds)
    }

    @After fun tearDown() = server.shutdown()

    private fun hostPort() = "${server.hostName}:${server.port}"
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)
    private val loginOk = """{"success":200,"message":"ok","data":{"token":{"access_token":"A1","refresh_token":"R1","expires_at":1},"user":{"username":"klaus"}}}"""
    private val utilOk = """{"success":200,"data":{"cpu":{"percent":10,"num":2},"mem":{"total":10,"used":5}}}"""

    @Test fun `login stores tokens and sends credentials as json`() = runTest {
        server.enqueue(json(loginOk))
        client.login(hostPort(), "klaus", "pw")

        assertEquals("A1", creds.accessToken)
        assertEquals("R1", creds.refreshToken)
        assertEquals("http://${hostPort()}", creds.baseUrl)
        val req = server.takeRequest()
        assertEquals("/v1/users/login", req.path)
        assertEquals("""{"username":"klaus","password":"pw"}""", req.body.readUtf8())
    }

    @Test fun `login surfaces the server's message on bad credentials`() = runTest {
        server.enqueue(json("""{"success":10006,"message":"Wrong username or password"}""", 400))
        val e = assertThrows(CasaException.Server::class.java) { runTestBlocking { client.login(hostPort(), "klaus", "bad") } }
        assertEquals("Wrong username or password", e.message)
        assertNull(creds.accessToken)
    }

    @Test fun `login fails when a v1 envelope reports an error inside HTTP 200`() = runTest {
        server.enqueue(json("""{"success":500,"message":"db locked"}"""))
        val e = assertThrows(CasaException.Server::class.java) { runTestBlocking { client.login(hostPort(), "a", "b") } }
        assertEquals("db locked", e.message)
    }

    @Test fun `requests carry the raw token with no Bearer prefix`() = runTest {
        server.enqueue(json(loginOk)); server.enqueue(json(utilOk))
        client.login(hostPort(), "klaus", "pw"); server.takeRequest()
        client.utilization()
        assertEquals("A1", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `expired access token is refreshed once and the request retried`() = runTest {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()

        server.enqueue(json("""{"message":"expired"}""", 401))
        server.enqueue(json("""{"success":200,"data":{"access_token":"A2","refresh_token":"R2","expires_at":2}}"""))
        server.enqueue(json(utilOk))

        assertEquals(10.0, client.utilization().cpuPercent, 0.0)

        assertEquals("A1", server.takeRequest().getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/v1/users/refresh", refresh.path)
        assertEquals("""{"refresh_token":"R1"}""", refresh.body.readUtf8())
        assertEquals("A2", server.takeRequest().getHeader("Authorization"))
        assertEquals("A2", creds.accessToken)
        assertEquals("R2", creds.refreshToken)
    }

    @Test fun `rejected refresh token signs the user out`() = runTest {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()
        server.enqueue(json("{}", 401))                       // original request
        server.enqueue(json("""{"message":"nope"}""", 401))   // refresh rejected

        assertThrows(CasaException.Unauthorized::class.java) { runTestBlocking { client.utilization() } }
        assertTrue(creds.signedOut)
    }

    @Test fun `network failure during refresh does not sign the user out`() = runTest {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()
        server.enqueue(json("{}", 401))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))

        assertThrows(CasaException.Unauthorized::class.java) { runTestBlocking { client.utilization() } }
        assertTrue("an offline blip must keep the session", !creds.signedOut)
    }

    @Test fun `the token is never sent to or refreshed for another host`() = runTest {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()

        // A second server stands in for a third-party icon CDN, reached through the shared client.
        MockWebServer().use { cdn ->
            cdn.start()
            cdn.enqueue(MockResponse().setResponseCode(401))
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("png"))
            val resp = client.http.newCall(Request.Builder().url(cdn.url("/icon.png")).build()).execute()
            resp.close()

            assertEquals(401, resp.code) // no retry
            assertEquals(1, cdn.requestCount)
            assertNull("token leaked to a third-party host", cdn.takeRequest().getHeader("Authorization"))
        }
        assertEquals(0, server.requestCount - 1) // no refresh call was made to our server either
    }

    @Test fun `set app status sends a bare json string`() = runTest {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()
        server.enqueue(json("""{"message":"ok"}"""))
        client.setAppStatus("my app", AppAction.Restart)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/v2/app_management/compose/my%20app/status", req.path)
        assertEquals("\"restart\"", req.body.readUtf8())
    }

    @Test fun `app logs come back as plain text`() = runTest {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()
        server.enqueue(json("""{"message":"ok","data":"line1\nline2"}"""))
        assertEquals("line1\nline2", client.appLogs("x", 50))
        assertEquals("/v2/app_management/compose/x/logs?lines=50", server.takeRequest().path)
    }

    @Test fun `app settings are fetched as raw YAML with an exact Accept header`() = runTest {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()
        val yaml = "name: jellyfin\nservices:\n  jellyfin:\n    image: linuxserver/jellyfin\n"
        server.enqueue(MockResponse().setBody(yaml).setHeader("Content-Type", "text/plain; charset=UTF-8"))
        assertEquals(yaml, client.appComposeYaml("my app"))
        val req = server.takeRequest()
        assertEquals("/v2/app_management/compose/my%20app", req.path)
        // CasaOS compares the header as a plain string; anything extra gets its debug JSON instead.
        assertEquals("application/yaml", req.getHeader("Accept"))
    }

    @Test fun `saving app settings puts the YAML and checks ports`() = runTest {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()
        server.enqueue(json("""{"message":"compose app is being applied with changes asynchroniously"}"""))
        client.applyAppSettings("jellyfin", "services: {}\n")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/v2/app_management/compose/jellyfin?check_port_conflict=true", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/yaml"))
        assertEquals("services: {}\n", req.body.readUtf8())
    }

    @Test fun `a port clash says which port`() = runTest {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()
        server.enqueue(json("""{"message":"there are ports in use","data":{"ports_in_use":{"TCP":["8080"],"UDP":["1900"]}}}""", 400))
        val e = assertThrows(CasaException.Server::class.java) { runTestBlocking { client.applyAppSettings("x", "services: {}") } }
        assertEquals("Ports 8080, 1900/udp are already used by something else on the server.", e.message)

        server.enqueue(json("""{"message":"yaml: line 3: mapping values are not allowed"}""", 400))
        val e2 = assertThrows(CasaException.Server::class.java) { runTestBlocking { client.applyAppSettings("x", "services: {}") } }
        assertEquals("yaml: line 3: mapping values are not allowed", e2.message)
    }

    @Test fun `folder listing encodes the path and parses entries`() = runTest {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()
        server.enqueue(json("""{"success":200,"data":{"content":[{"name":"x y.txt","is_dir":false,"size":3,"path":"/DATA/a b/x y.txt"}]}}"""))
        val listing = client.folder("/DATA/a b")
        assertEquals("/DATA/a b/x y.txt", listing.entries.single().path)
        assertTrue(server.takeRequest().path!!.startsWith("/v1/folder?path=%2FDATA%2Fa%20b"))
    }

    @Test fun `unreachable server maps to a network error`() = runTest {
        server.shutdown()
        assertThrows(CasaException.Network::class.java) { runTestBlocking { client.login("127.0.0.1:1", "a", "b") } }
    }

    /** assertThrows takes a non-suspending lambda; this bridges to the suspend API. */
    private fun runTestBlocking(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
