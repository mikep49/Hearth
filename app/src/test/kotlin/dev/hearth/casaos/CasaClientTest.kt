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

    @Test fun `updating an app patches its compose app`() = runTest {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()
        server.enqueue(json("""{"message":"compose app is being updated asynchronously"}"""))
        client.updateApp("jellyfin")
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/v2/app_management/compose/jellyfin", req.path)
        assertEquals("A1", req.getHeader("Authorization"))
    }

    @Test fun `the store's version comes from the stable endpoint, and is null for non-store apps`() = runTest {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()
        server.enqueue(json("""{"message":"ok","data":{"tag":"10.9.1"}}"""))
        assertEquals("10.9.1", client.storeStableTag("jellyfin"))
        assertEquals("/v2/app_management/apps/jellyfin/stable", server.takeRequest().path)

        server.enqueue(json("""{"message":"Not Found"}""", 404))
        assertNull(client.storeStableTag("my-custom-app"))
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

    private suspend fun signedIn() {
        server.enqueue(json(loginOk)); client.login(hostPort(), "klaus", "pw"); server.takeRequest()
    }

    private val ok = """{"success":200,"message":"ok","data":null}"""

    @Test fun `file actions send what the CasaOS dashboard sends`() = runTest {
        signedIn()
        repeat(4) { server.enqueue(json(ok)) }

        client.createFolder("/DATA/New folder")
        server.takeRequest().let {
            assertEquals("POST", it.method); assertEquals("/v1/folder", it.path)
            assertEquals("""{"path":"/DATA/New folder"}""", it.body.readUtf8())
        }
        client.rename("/DATA/a.txt", "/DATA/b.txt")
        server.takeRequest().let {
            assertEquals("PUT", it.method); assertEquals("/v1/file/name", it.path)
            assertEquals("""{"old_path":"/DATA/a.txt","new_path":"/DATA/b.txt"}""", it.body.readUtf8())
        }
        client.delete(listOf("/DATA/a", "/DATA/b"))
        server.takeRequest().let {
            assertEquals("DELETE", it.method); assertEquals("/v1/file", it.path)
            assertEquals("""["/DATA/a","/DATA/b"]""", it.body.readUtf8())
        }
        client.transfer(listOf("/DATA/a"), "/DATA/Media", move = true)
        server.takeRequest().let {
            assertEquals("POST", it.method); assertEquals("/v1/batch/task", it.path)
            assertEquals("""{"type":"move","to":"/DATA/Media","style":"skip","item":[{"from":"/DATA/a"}]}""", it.body.readUtf8())
        }
    }

    @Test fun `a v1 failure inside a 200 is an error`() = runTest {
        signedIn()
        server.enqueue(json("""{"success":500,"message":"file exists"}"""))
        val e = assertThrows(CasaException.Server::class.java) { runTestBlocking { client.createFolder("/DATA/x") } }
        assertEquals("file exists", e.message)
    }

    @Test fun `uploads are split into numbered chunks with the folder and name`() = runTest {
        signedIn()
        val size = 1024 * 1024 + 10 // one full chunk and a 10-byte one
        repeat(2) { server.enqueue(json(ok)) }
        val progress = mutableListOf<Long>()
        client.upload("/DATA/Photos", "cat.jpg", size.toLong(), ByteArray(size) { 7 }.inputStream()) { done, _ -> progress += done }

        val first = server.takeRequest()
        assertEquals("/v1/file/upload", first.path)
        val body1 = first.body.readUtf8()
        assertEquals("1", formField(body1, "chunkNumber"))
        assertEquals("2", formField(body1, "totalChunks"))
        assertEquals("/DATA/Photos", formField(body1, "path"))
        assertEquals("cat.jpg", formField(body1, "relativePath"))
        assertTrue(body1.contains("name=\"file\"; filename=\"cat.jpg\""))
        val body2 = server.takeRequest().body.readUtf8()
        assertEquals("2", formField(body2, "chunkNumber"))
        assertEquals("10", formField(body2, "currentChunkSize"))
        assertEquals(listOf(1024L * 1024, size.toLong()), progress)
    }

    @Test fun `installing fetches the store's compose file and posts it back`() = runTest {
        signedIn()
        val yaml = "name: jellyfin\nservices: {}\n"
        server.enqueue(MockResponse().setBody(yaml))
        server.enqueue(json("""{"message":"compose app is being installed asynchronously"}"""))
        client.installStoreApp("jellyfin")

        val get = server.takeRequest()
        assertEquals("/v2/app_management/apps/jellyfin/compose", get.path)
        assertEquals("application/yaml", get.getHeader("Accept"))
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/v2/app_management/compose?check_port_conflict=true", post.path)
        assertEquals(yaml, post.body.readUtf8())
    }

    @Test fun `uninstalling deletes the compose app, keeping data unless asked`() = runTest {
        signedIn()
        repeat(2) { server.enqueue(json("""{"message":"ok"}""")) }
        client.uninstallApp("jellyfin", deleteData = false)
        server.takeRequest().let {
            assertEquals("DELETE", it.method)
            assertEquals("/v2/app_management/compose/jellyfin?delete_config_folder=false", it.path)
        }
        client.uninstallApp("jellyfin", deleteData = true)
        assertEquals("/v2/app_management/compose/jellyfin?delete_config_folder=true", server.takeRequest().path)
    }

    @Test fun `previews read only the start of a file`() = runTest {
        signedIn()
        server.enqueue(MockResponse().setBody("x".repeat(5000)))
        assertEquals(100, client.readStart("/DATA/log.txt", 100).size)
        assertEquals("/v1/file?path=%2FDATA%2Flog.txt", server.takeRequest().path)
    }

    @Test fun `reading from an offset asks for that range, signed in`() = runTest {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(206).setBody("lo world"))
        val text = client.openFrom("/DATA/a.txt", 3).use { it.readBytes().decodeToString() }
        assertEquals("lo world", text)
        val req = server.takeRequest()
        assertEquals("bytes=3-", req.getHeader("Range"))
        assertEquals("A1", req.getHeader("Authorization"))
        assertEquals("/v1/file?path=%2FDATA%2Fa.txt", req.path)
    }

    @Test fun `a server that ignores the range has the start skipped`() = runTest {
        signedIn()
        server.enqueue(MockResponse().setBody("hello world"))
        assertEquals("world", client.openFrom("/DATA/a.txt", 6).use { it.readBytes().decodeToString() })
        server.enqueue(MockResponse().setBody("hello"))
        assertEquals("hello", client.openFrom("/DATA/a.txt", 0).use { it.readBytes().decodeToString() })
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("Range")) // the whole file needs no range
    }

    @Test fun `reading from the very end gives nothing, and errors say why`() = runTest {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(416))
        assertEquals(0, client.openFrom("/DATA/a.txt", 5).use { it.readBytes().size })
        server.enqueue(json("""{"message":"not found"}""", 404))
        assertThrows(CasaException.Server::class.java) { client.openFrom("/DATA/gone.txt", 0) }
    }

    /** The value of a plain field in a multipart form body. */
    private fun formField(body: String, name: String): String? =
        Regex("name=\"$name\"\r\n(?:[^\r\n]+\r\n)*\r\n([^\r]*)\r\n").find(body)?.groupValues?.get(1)

    /** assertThrows takes a non-suspending lambda; this bridges to the suspend API. */
    private fun runTestBlocking(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
