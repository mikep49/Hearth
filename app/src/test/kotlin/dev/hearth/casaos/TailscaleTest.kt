package dev.hearth.casaos

import dev.hearth.casaos.data.HostKeyStore
import dev.hearth.casaos.data.PhoneTailscale
import dev.hearth.casaos.data.ServerRoute
import dev.hearth.casaos.data.SshProfile
import dev.hearth.casaos.data.SshUnavailable
import dev.hearth.casaos.data.TailscaleBackend
import dev.hearth.casaos.data.TailscalePath
import dev.hearth.casaos.data.TailscaleSource
import dev.hearth.casaos.data.isTailscaleAddress
import dev.hearth.casaos.data.parseTailscaleStatus
import dev.hearth.casaos.data.lookUpServerRoute
import dev.hearth.casaos.data.serverRoute
import dev.hearth.casaos.data.tailscaleContainer
import dev.hearth.casaos.data.tailscaleError
import dev.hearth.casaos.ui.settings.ConnectionSummary
import dev.hearth.casaos.ui.settings.ServerTailscale
import dev.hearth.casaos.ui.settings.TailscaleState
import dev.hearth.casaos.ui.settings.connectionSummary
import dev.hearth.casaos.ui.settings.exitNodeText
import dev.hearth.casaos.ui.settings.failed
import dev.hearth.casaos.ui.settings.pathText
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Collections
import kotlin.concurrent.thread

/** A server on a tailnet with a phone it talks to directly, a laptop it reaches through a relay, and an offline exit node. */
private val STATUS = """
    {
      "Version": "1.80.2-t1a2b3c4d5-g6e7f8a9b0",
      "BackendState": "Running",
      "TailscaleIPs": ["100.101.102.103", "fd7a:115c:a1e0::1"],
      "Self": {
        "ID": "n1", "HostName": "casaos-box", "DNSName": "casaos.tail1234.ts.net.", "OS": "linux", "UserID": 42,
        "TailscaleIPs": ["100.101.102.103", "fd7a:115c:a1e0::1"], "CurAddr": "", "Relay": "fra",
        "RxBytes": 0, "TxBytes": 0, "Online": true, "ExitNode": false, "ExitNodeOption": true,
        "PrimaryRoutes": ["192.168.1.0/24"], "KeyExpiry": "2027-05-01T12:00:00Z"
      },
      "Health": ["Tailscale can't reach the configured DNS servers."],
      "MagicDNSSuffix": "tail1234.ts.net",
      "CurrentTailnet": {"Name": "mike@example.com", "MagicDNSSuffix": "tail1234.ts.net", "MagicDNSEnabled": true},
      "Peer": {
        "nodekey:aaa": {
          "ID": "n2", "HostName": "Pixel 9", "DNSName": "pixel-9.tail1234.ts.net.", "OS": "android", "UserID": 42,
          "TailscaleIPs": ["100.64.0.7", "fd7a:115c:a1e0::7"], "CurAddr": "203.0.113.5:41641", "Relay": "fra",
          "RxBytes": 2048, "TxBytes": 1048576, "Online": true, "Active": true, "ExitNode": false
        },
        "nodekey:bbb": {
          "ID": "n3", "HostName": "laptop", "DNSName": "laptop.tail1234.ts.net.", "OS": "windows", "UserID": 42,
          "TailscaleIPs": ["100.64.0.8"], "CurAddr": "", "Relay": "nyc", "Online": true, "Active": true
        },
        "nodekey:ccc": {
          "ID": "n4", "HostName": "vps", "DNSName": "", "OS": "linux", "UserID": 42,
          "TailscaleIPs": ["100.64.0.9"], "CurAddr": "", "Relay": "", "Online": false, "Active": false, "ExitNode": true
        }
      },
      "User": {"42": {"ID": 42, "LoginName": "mike@example.com", "DisplayName": "Mike"}}
    }
""".trimIndent()

class TailscaleParsingTest {
    private val status = parseTailscaleStatus(STATUS)!!

    @Test fun `reads the server's own details`() {
        assertEquals(TailscaleBackend.Running, status.backend)
        assertEquals("the build's commits are left off", "1.80.2", status.version)
        assertEquals("mike@example.com", status.tailnet)
        assertEquals("mike@example.com", status.account)
        assertNull(status.container)
        val self = status.self!!
        assertEquals("the name it has on the tailnet, not the machine's own", "casaos", self.name)
        assertEquals("casaos.tail1234.ts.net", self.dnsName)
        assertEquals("100.101.102.103", self.address)
        assertEquals(listOf("192.168.1.0/24"), self.subnets)
        assertEquals(Instant.parse("2027-05-01T12:00:00Z"), self.keyExpiry)
        assertEquals(listOf("Tailscale can't reach the configured DNS servers."), status.health)
    }

    @Test fun `reads each device and how the server reaches it`() {
        assertEquals(listOf("pixel-9", "laptop", "vps"), status.peers.map { it.name })
        val (phone, laptop, vps) = status.peers
        assertEquals(TailscalePath.Direct, phone.path)
        assertEquals("203.0.113.5:41641", phone.directAddress)
        assertEquals(2048L, phone.rxBytes)
        assertEquals(1_048_576L, phone.txBytes)
        assertEquals("Direct", pathText(phone))
        assertEquals(TailscalePath.Relayed, laptop.path)
        assertEquals("Relayed through nyc", pathText(laptop))
        assertEquals(TailscalePath.Idle, vps.path)
        assertFalse(vps.online)
        assertEquals("a device with no tailnet name falls back to the machine's", "vps", vps.name)
        assertNull(vps.dnsName)
        assertNull(vps.keyExpiry)
    }

    @Test fun `finds the phone among the devices by its address`() {
        assertEquals("pixel-9", status.deviceWith(listOf("fd7a:115c:a1e0::7"))?.name)
        assertNull(status.deviceWith(listOf("100.64.0.99")))
        assertNull("a phone with Tailscale off has no address to match", status.deviceWith(emptyList()))
    }

    @Test fun `says what the server does about exit nodes`() {
        assertEquals("Uses vps", exitNodeText(status))
        val none = status.copy(peers = status.peers.take(2))
        assertEquals("This server offers to be one", exitNodeText(none))
        assertNull(exitNodeText(none.copy(self = none.self!!.copy(offersExitNode = false))))
    }

    @Test fun `a signed-out or stopped Tailscale still parses`() {
        val out = parseTailscaleStatus("""{"BackendState":"NeedsLogin","Version":"1.80.2","Self":{"HostName":"casaos","Online":false},"Peer":null}""")!!
        assertEquals(TailscaleBackend.NeedsLogin, out.backend)
        assertEquals("casaos", out.self?.name)
        assertTrue(out.peers.isEmpty())
        assertEquals(TailscaleBackend.Stopped, parseTailscaleStatus("""{"BackendState":"Stopped"}""")?.backend)
        assertEquals("a state from a newer Tailscale", TailscaleBackend.Unknown, parseTailscaleStatus("""{"BackendState":"Napping"}""")?.backend)
    }

    @Test fun `anything that isn't a status is refused`() {
        assertNull(parseTailscaleStatus(""))
        assertNull(parseTailscaleStatus("Tailscale is stopped."))
        assertNull(parseTailscaleStatus("""{"error":"nope"}"""))
    }

    @Test fun `tells Tailscale's addresses from other networks'`() {
        assertTrue(isTailscaleAddress("100.64.0.1"))
        assertTrue(isTailscaleAddress("100.127.255.254"))
        assertTrue(isTailscaleAddress("fd7a:115c:a1e0::1"))
        assertTrue("as a URL writes it", isTailscaleAddress("[FD7A:115C:A1E0:ab12::1]"))
        assertFalse("just below the range", isTailscaleAddress("100.63.255.255"))
        assertFalse("just above it", isTailscaleAddress("100.128.0.1"))
        assertFalse(isTailscaleAddress("192.168.1.20"))
        assertFalse(isTailscaleAddress("fd00::1"))
        assertFalse(isTailscaleAddress("casaos.local"))
    }

    @Test fun `works out how the server is reached from its address`() {
        assertEquals(ServerRoute.Tailscale, serverRoute("100.101.102.103"))
        assertEquals(ServerRoute.Tailscale, serverRoute("casaos.tail1234.ts.net"))
        assertEquals("a short MagicDNS name only shows once it's looked up", ServerRoute.Other, serverRoute("casaos"))
        assertEquals(ServerRoute.Tailscale, serverRoute("casaos", listOf("100.101.102.103")))
        assertEquals(ServerRoute.LocalNetwork, serverRoute("192.168.1.20"))
        assertEquals(ServerRoute.LocalNetwork, serverRoute("10.0.0.5"))
        assertEquals(ServerRoute.LocalNetwork, serverRoute("172.20.0.5"))
        assertEquals(ServerRoute.LocalNetwork, serverRoute("casaos.local"))
        assertEquals(ServerRoute.LocalNetwork, serverRoute("nas", listOf("192.168.1.20")))
        assertEquals(ServerRoute.Other, serverRoute("172.32.0.5"))
        assertEquals(ServerRoute.Other, serverRoute("home.example.com", listOf("203.0.113.5")))
    }

    @Test fun `looks a name up only when the name doesn't say how it's reached`() = runBlocking {
        val looked = mutableListOf<String>()
        fun dns(vararg addresses: String): (String) -> List<String> = { name -> looked += name; addresses.toList() }

        assertEquals(ServerRoute.Tailscale, lookUpServerRoute("casaos", dns("100.101.102.103")))
        assertEquals(ServerRoute.LocalNetwork, lookUpServerRoute("nas.lan", dns("192.168.1.20")))
        assertEquals(ServerRoute.Other, lookUpServerRoute("home.example.com", dns("203.0.113.5")))
        assertEquals(listOf("casaos", "nas.lan", "home.example.com"), looked)

        looked.clear()
        assertEquals(ServerRoute.LocalNetwork, lookUpServerRoute("casaos.local", dns("100.101.102.103")))
        assertEquals(ServerRoute.Tailscale, lookUpServerRoute("casaos.tail1234.ts.net", dns()))
        assertEquals(ServerRoute.Other, lookUpServerRoute("203.0.113.5", dns("192.168.1.20")))
        assertEquals("names and addresses that already say aren't looked up", emptyList<String>(), looked)

        assertEquals("a failed lookup falls back to the name", ServerRoute.Other, lookUpServerRoute("casaos") { throw java.net.UnknownHostException(it) })
    }

    @Test fun `picks the Tailscale container out of docker ps`() {
        val ps = "jellyfin|linuxserver/jellyfin:latest\nts|ghcr.io/tailscale/tailscale:v1.80.2\n"
        assertEquals("ts", tailscaleContainer(ps))
        assertEquals("tailscale", tailscaleContainer("tailscale|tailscale/tailscale\n"))
        assertNull(tailscaleContainer("jellyfin|linuxserver/jellyfin:latest\n"))
        assertNull("a tag that mentions it isn't the image", tailscaleContainer("proxy|caddy:with-tailscale\n"))
        assertNull("a name that could end the command is never used", tailscaleContainer("x; reboot|tailscale/tailscale\n"))
        assertNull(tailscaleContainer(""))
    }

    @Test fun `explains why Tailscale didn't answer`() {
        assertEquals(
            "Tailscale is installed on this server, but it isn't running.",
            tailscaleError("failed to connect to local tailscaled; it doesn't appear to be running (sudo systemctl start tailscaled ?)\n"),
        )
        assertEquals("Tailscale: access denied", tailscaleError("\naccess denied\n"))
        assertEquals("Tailscale didn't answer.", tailscaleError(""))
    }
}

class TailscalePageTest {
    private val status = parseTailscaleStatus(STATUS)!!
    private val phoneOn = PhoneTailscale(listOf("100.64.0.7"), appInstalled = true)
    private val ready = ServerTailscale.Ready(status)

    private fun summary(route: ServerRoute, phone: PhoneTailscale, server: ServerTailscale, address: String? = "100.64.0.7") =
        connectionSummary("casaos", TailscaleState(phone, route, server), address?.let { status.deviceWith(listOf(it)) })

    @Test fun `through Tailscale, it says whether the path is direct`() {
        val direct = summary(ServerRoute.Tailscale, phoneOn, ready)
        assertEquals(ConnectionSummary.Kind.Tailscale, direct.kind)
        assertTrue("directly" in direct.text)
        val relayed = summary(ServerRoute.Tailscale, phoneOn.copy(addresses = listOf("100.64.0.8")), ready, address = "100.64.0.8")
        assertTrue("nyc relay" in relayed.text)
        val unknown = summary(ServerRoute.Tailscale, phoneOn, ServerTailscale.Loading, address = null)
        assertEquals(ConnectionSummary.Kind.Tailscale, unknown.kind)
        assertTrue("casaos" in unknown.text)
    }

    @Test fun `a Tailscale address with Tailscale off is flagged, unless the server answers anyway`() {
        val off = PhoneTailscale(appInstalled = true)
        assertEquals(ConnectionSummary.Kind.Problem, summary(ServerRoute.Tailscale, off, ServerTailscale.Loading, address = null).kind)
        assertEquals(
            ConnectionSummary.Kind.Problem,
            summary(ServerRoute.Tailscale, off, ServerTailscale.Unavailable("The server took too long to respond", false), address = null).kind,
        )
        assertEquals(ConnectionSummary.Kind.Tailscale, summary(ServerRoute.Tailscale, off, ready, address = null).kind)
    }

    @Test fun `other routes say Tailscale isn't in use`() {
        assertEquals(ConnectionSummary.Kind.Local, summary(ServerRoute.LocalNetwork, phoneOn, ready).kind)
        assertEquals(ConnectionSummary.Kind.Other, summary(ServerRoute.Other, PhoneTailscale(), ServerTailscale.NotInstalled, address = null).kind)
    }

    @Test fun `a failed reading keeps the last status, but sign-in problems replace it`() {
        assertEquals(ServerTailscale.Ready(status, "Connection lost"), ready.failed(SshUnavailable("Connection lost")))
        assertEquals(ServerTailscale.Unavailable("Sign in", needsSignIn = true), ready.failed(SshUnavailable("Sign in", needsSignIn = true)))
        assertEquals(ServerTailscale.Unavailable("x", false), ServerTailscale.Loading.failed(SshUnavailable("x")))
        assertEquals(ServerTailscale.Unavailable("x", false), ServerTailscale.NotInstalled.failed(SshUnavailable("x")))
    }
}

/** Runs [TailscaleSource] against a real SSH server in the test process, with a pretend `tailscale` and `docker`. */
class TailscaleSourceTest {
    private lateinit var server: SshServer
    private val keys = MemoryKeys()
    private val commands = Collections.synchronizedList(mutableListOf<String>())

    /** Where Tailscale is: on the server itself, in a container, or nowhere. */
    private enum class Install { Server, Container, None }

    @Volatile private var install = Install.Server
    @Volatile private var hasDocker = true

    /** Whether the Tailscale service is running, where it's installed on the server itself. */
    @Volatile private var daemonUp = true

    @Before fun start() {
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { user, password, _ -> user == "casa" && password == "secret" }
            setCommandFactory { _, command -> commands += command; FakeServer(command) }
            start()
        }
        // What the Terminal tab stores once the user accepts the fingerprint.
        keys.trust("127.0.0.1:${server.port}", PublicKeyEntry.toString(server.keyPairProvider.loadKeys(null).first().public).split(' ')[1])
    }

    @After fun stop() = server.stop(true)

    private fun source(password: String? = "secret") =
        TailscaleSource({ SshProfile("127.0.0.1", server.port, "casa", password) }, keys)

    @Test fun `reads Tailscale installed on the server itself`() = runBlocking {
        val s = source()
        val status = s.read()!!
        assertEquals("casaos", status.self?.name)
        assertNull(status.container)
        assertEquals(listOf("tailscale status --json"), commands.toList())
        s.close()
    }

    @Test fun `finds Tailscale running as an app, and asks it directly from then on`() = runBlocking {
        install = Install.Container
        val s = source()
        assertEquals("ts", s.read()?.container)
        assertEquals(
            listOf("tailscale status --json", "command -v docker", "docker ps --format '{{.Names}}|{{.Image}}'", "docker exec ts tailscale status --json"),
            commands.toList(),
        )
        commands.clear()
        assertEquals("ts", s.read()?.container)
        assertEquals(listOf("docker exec ts tailscale status --json"), commands.toList())
        s.close()
    }

    @Test fun `looks again when the app it found has gone`() = runBlocking {
        install = Install.Container
        val s = source()
        assertEquals("ts", s.read()?.container)
        install = Install.None
        assertNull(s.read())
        install = Install.Server
        assertNull("now on the server itself", s.read()?.container)
        s.close()
    }

    @Test fun `no Tailscale anywhere is not an error`() = runBlocking {
        install = Install.None
        val s = source()
        assertNull(s.read())
        hasDocker = false
        assertNull(s.read())
        assertEquals("docker isn't run where there's none", "command -v docker", commands.last())
        s.close()
    }

    @Test fun `a stopped Tailscale service is reported in plain words`() {
        daemonUp = false
        val s = source()
        val e = expect<SshUnavailable> { runBlocking { s.read() } }
        assertEquals("Tailscale is installed on this server, but it isn't running.", e.message)
        assertFalse(e.needsSignIn)
        runBlocking { s.close() }
    }

    @Test fun `no saved password asks the user to sign in on the Terminal tab`() {
        val e = expect<SshUnavailable> { runBlocking { source(password = null).read() } }
        assertTrue(e.needsSignIn)
        assertTrue("Tailscale" in e.message.orEmpty())
        assertTrue("nothing was run", commands.isEmpty())
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    private class MemoryKeys : HostKeyStore {
        private val map = mutableMapOf<String, String>()
        override fun hostKey(id: String) = map[id]
        override fun trust(id: String, keyBase64: String) {
            map[id] = keyBase64
        }
    }

    /** Answers the commands [TailscaleSource] runs, like a server set up as [install] says. */
    private inner class FakeServer(private val command: String) : Command {
        private lateinit var output: OutputStream
        private lateinit var error: OutputStream
        private lateinit var exit: ExitCallback

        override fun setInputStream(`in`: InputStream) = Unit
        override fun setOutputStream(out: OutputStream) { output = out }
        override fun setErrorStream(err: OutputStream) { error = err }
        override fun setExitCallback(callback: ExitCallback) { exit = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            thread(isDaemon = true) {
                val status = STATUS.replace("\n", " ") + "\n"
                val (code, out, err) = when {
                    command == "tailscale status --json" -> when {
                        install != Install.Server -> Triple(127, "", "bash: tailscale: command not found\n")
                        !daemonUp -> Triple(1, "", "failed to connect to local tailscaled; it doesn't appear to be running\n")
                        else -> Triple(0, status, "")
                    }
                    command == "command -v docker" -> if (hasDocker) Triple(0, "/usr/bin/docker\n", "") else Triple(1, "", "")
                    command.startsWith("docker ps") ->
                        Triple(0, "jellyfin|linuxserver/jellyfin:latest\n" + if (install == Install.Container) "ts|tailscale/tailscale:latest\n" else "", "")
                    command == "docker exec ts tailscale status --json" ->
                        if (install == Install.Container) Triple(0, status, "") else Triple(1, "", "Error response from daemon: No such container: ts\n")
                    else -> Triple(127, "", "unknown command\n")
                }
                output.write(out.toByteArray()); output.flush()
                error.write(err.toByteArray()); error.flush()
                exit.onExit(code)
            }
        }

        override fun destroy(channel: ChannelSession) = Unit
    }
}
