package dev.hearth.casaos

import dev.hearth.casaos.data.AppState
import dev.hearth.casaos.data.AppUsage
import dev.hearth.casaos.data.AppUsageSource
import dev.hearth.casaos.data.ContainerUsage
import dev.hearth.casaos.data.HostKeyStore
import dev.hearth.casaos.data.InstalledApp
import dev.hearth.casaos.data.SshKey
import dev.hearth.casaos.data.SshKeys
import dev.hearth.casaos.data.SshProfile
import dev.hearth.casaos.data.SshUnavailable
import dev.hearth.casaos.data.groupUsage
import dev.hearth.casaos.data.parseDockerSize
import dev.hearth.casaos.data.parseDockerUsage
import dev.hearth.casaos.ui.dashboard.AppsUsageState
import dev.hearth.casaos.ui.dashboard.failed
import dev.hearth.casaos.ui.dashboard.formatPercent
import dev.hearth.casaos.ui.dashboard.serverCpuShare
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import kotlin.concurrent.thread

class AppUsageParsingTest {
    private val ps = """
        jellyfin|jellyfin
        immich-server|immich
        immich-postgres|immich
        portainer|
    """.trimIndent()

    private val stats = """
        jellyfin|12.50%|512MiB / 7.6GiB
        immich-server|180.25%|1.5GiB / 7.6GiB
        immich-postgres|--|256MiB / 7.6GiB
        portainer|0.10%|20.4MB / 7.6GiB
        started-after-ps|1.00%|1KiB / 7.6GiB
    """.trimIndent()

    private fun app(id: String, name: String) = InstalledApp(
        id = id, name = name, tagline = null, category = null, iconUrl = "https://icons/$id.png",
        state = AppState.Running, rawStatus = "running", updateAvailable = false, webPort = null, webPath = "/", scheme = "http",
    )

    @Test fun `stats and owners are joined by container name`() {
        val c = parseDockerUsage(ps, stats)
        assertEquals(5, c.size)
        assertEquals(ContainerUsage("jellyfin", "jellyfin", 12.5, 512L * 1024 * 1024), c[0])
        assertEquals("immich", c[2].project)
        assertEquals("a container that's still starting reads as idle", 0.0, c[2].cpuPercent, 0.0)
        assertNull("no compose project", c[3].project)
        assertNull("missing from ps", c[4].project)
    }

    @Test fun `sizes in both binary and decimal units`() {
        assertEquals(0L, parseDockerSize("0B"))
        assertEquals(1536L, parseDockerSize("1.5KiB"))
        assertEquals(20_400_000L, parseDockerSize(" 20.4MB "))
        assertEquals(2L * 1024 * 1024 * 1024, parseDockerSize("2GiB"))
        assertNull(parseDockerSize("lots"))
    }

    @Test fun `containers add up into their CasaOS app, busiest first`() {
        val apps = listOf(app("jellyfin", "Jellyfin"), app("immich", "Immich"))
        val grouped = groupUsage(parseDockerUsage(ps, stats), apps)
        assertEquals(listOf("immich", "jellyfin", "started-after-ps", "portainer"), grouped.map { it.id })
        val immich = grouped[0]
        assertEquals("Immich", immich.name)
        assertEquals("https://icons/immich.png", immich.iconUrl)
        assertEquals("its containers, busiest first", listOf("immich-server", "immich-postgres"), immich.containers.map { it.name })
        assertEquals(180.25, immich.cpuPercent, 1e-9)
        assertEquals(1536L * 1024 * 1024 + 256L * 1024 * 1024, immich.memBytes)
        assertEquals("a container from outside CasaOS keeps its own name", "portainer", grouped[3].name)
        assertNull(grouped[3].iconUrl)
    }

    @Test fun `cpu is shown as a share of the whole server`() {
        assertEquals(45.0, serverCpuShare(180.0, 4), 1e-9)
        assertEquals("unknown core count leaves Docker's figure", 180.0, serverCpuShare(180.0, 0), 1e-9)
        assertEquals("0%", formatPercent(0.0))
        assertEquals("<1%", formatPercent(0.4))
        assertEquals("2.5%", formatPercent(2.46))
        assertEquals("45%", formatPercent(45.2))
    }

    @Test fun `a failed reading keeps the last list, but sign-in problems replace it`() {
        val ready = AppsUsageState.Ready(listOf(AppUsage("a", "A", null, 1.0, 1, emptyList())))
        val stale = ready.failed(SshUnavailable("Connection lost"))
        assertEquals(AppsUsageState.Ready(ready.apps, "Connection lost"), stale)
        val signIn = ready.failed(SshUnavailable("Sign in", needsSignIn = true))
        assertEquals(AppsUsageState.Unavailable("Sign in", needsSignIn = true), signIn)
        assertEquals(AppsUsageState.Unavailable("x", false), AppsUsageState.Loading.failed(SshUnavailable("x")))
    }
}

/** Runs [AppUsageSource] against a real SSH server in the test process, with a pretend `docker` and `sudo`. */
class AppUsageSourceTest {
    private lateinit var server: SshServer
    private val keys = MemoryKeys()
    private val commands = Collections.synchronizedList(mutableListOf<String>())

    /** Whether the SSH user can talk to Docker without sudo. */
    @Volatile private var inDockerGroup = true

    /** Whether sudo lets the SSH user through without asking for a password. */
    @Volatile private var passwordlessSudo = false

    /** The one key the server takes. */
    private val key = SshKeys.generate()

    @Before fun start() {
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { user, password, _ -> user == "casa" && password == "secret" }
            val known = AuthorizedKeyEntry.parseAuthorizedKeyEntry(key.publicKey).resolvePublicKey(null, PublicKeyEntryResolver.FAILING)
            setPublickeyAuthenticator { user, offered, _ -> user == "casa" && KeyUtils.compareKeys(known, offered) }
            setCommandFactory { _, command -> commands += command; FakeDocker(command) }
            start()
        }
    }

    @After fun stop() = server.stop(true)

    private fun profile(password: String? = "secret") = SshProfile("127.0.0.1", server.port, "casa", password)

    private fun source(saved: () -> SshProfile = { profile() }) = AppUsageSource(saved, keys)

    private fun trust() {
        val e = expect<SshUnavailable> { runBlocking { source().sample() } }
        assertTrue("an unverified server sends the user to the Terminal tab", e.needsSignIn)
        val id = "127.0.0.1:${server.port}"
        // What the Terminal tab stores once the user accepts the fingerprint.
        keys.trust(id, org.apache.sshd.common.config.keys.PublicKeyEntry.toString(server.keyPairProvider.loadKeys(null).first().public)
            .split(' ')[1])
    }

    @Test fun `no saved password asks the user to sign in on the Terminal tab`() {
        val e = expect<SshUnavailable> { runBlocking { source { profile(password = null) }.sample() } }
        assertTrue(e.needsSignIn)
        assertTrue("nothing was run", commands.isEmpty())
    }

    @Test fun `reads usage over one login`() = runBlocking {
        trust()
        val s = source()
        val first = s.sample()
        assertEquals(listOf(ContainerUsage("jellyfin", "jellyfin", 12.5, 512L * 1024 * 1024)), first)
        s.sample()
        assertEquals(4, commands.size)
        assertTrue(commands.all { it.startsWith("docker ") })
        s.close()
    }

    @Test fun `falls back to sudo when the user isn't in the docker group`() = runBlocking {
        trust()
        inDockerGroup = false
        val s = source()
        assertEquals(1, s.sample().size)
        assertEquals("docker ps", commands[0].substringBefore(" --"))
        assertTrue(commands[1].startsWith("sudo -S -p '' docker ps"))
        commands.clear()
        s.sample()
        assertTrue("remembers that sudo is needed", commands.all { it.startsWith("sudo ") })
        s.close()
    }

    @Test fun `a wrong saved password asks the user to sign in again`() {
        trust()
        val e = expect<SshUnavailable> { runBlocking { source { profile(password = "old") }.sample() } }
        assertTrue(e.needsSignIn)
    }

    private fun keyProfile(key: SshKey = this.key, useKey: Boolean = true) = profile(password = null).copy(key = key, useKey = useKey)

    @Test fun `a saved key works in place of a password`() = runBlocking {
        trust()
        val s = source { keyProfile() }
        assertEquals(1, s.sample().size)
        s.close()
    }

    @Test fun `a saved key is only used when it is the chosen way to sign in`() {
        val e = expect<SshUnavailable> { runBlocking { source { keyProfile(useKey = false) }.sample() } }
        assertTrue(e.needsSignIn)
        assertTrue("nothing was run", commands.isEmpty())
    }

    @Test fun `a key the server doesn't take asks the user to sign in again`() {
        trust()
        val e = expect<SshUnavailable> { runBlocking { source { keyProfile(SshKeys.generate()) }.sample() } }
        assertTrue(e.needsSignIn)
        assertTrue("SSH key" in e.message.orEmpty())
    }

    @Test fun `with a key, sudo is only tried where it asks for no password`() = runBlocking {
        trust()
        inDockerGroup = false
        val s = source { keyProfile() }
        val e = expect<SshUnavailable> { runBlocking { s.sample() } }
        assertTrue("says how to fix it", "docker group" in e.message.orEmpty())
        assertTrue(commands.last().startsWith("sudo -n docker ps"))

        passwordlessSudo = true
        assertEquals(1, s.sample().size)
        s.close()
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

    /** Answers `docker ps` / `docker stats` like a server with one app, `sudo -S` when given the password, and `sudo -n` if allowed. */
    private inner class FakeDocker(private val command: String) : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var error: OutputStream
        private lateinit var exit: ExitCallback

        override fun setInputStream(`in`: InputStream) { input = `in` }
        override fun setOutputStream(out: OutputStream) { output = out }
        override fun setErrorStream(err: OutputStream) { error = err }
        override fun setExitCallback(callback: ExitCallback) { exit = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            thread(isDaemon = true) {
                val sudo = command.startsWith("sudo -S -p '' ")
                val quietSudo = command.startsWith("sudo -n ")
                val docker = command.removePrefix("sudo -S -p '' ").removePrefix("sudo -n ")
                if (sudo && input.bufferedReader().readLine() != "secret") {
                    error.write("Sorry, try again.\n".toByteArray()); error.flush(); exit.onExit(1); return@thread
                }
                if (quietSudo && !passwordlessSudo) {
                    error.write("sudo: a password is required\n".toByteArray()); error.flush(); exit.onExit(1); return@thread
                }
                if (!sudo && !quietSudo && !inDockerGroup) {
                    error.write("permission denied while trying to connect to the Docker daemon socket\n".toByteArray())
                    error.flush(); exit.onExit(1); return@thread
                }
                val out = when {
                    docker.startsWith("docker ps") -> "jellyfin|jellyfin\n"
                    docker.startsWith("docker stats") -> "jellyfin|12.50%|512MiB / 7.6GiB\n"
                    else -> null
                }
                if (out == null) {
                    error.write("unknown command\n".toByteArray()); error.flush(); exit.onExit(127); return@thread
                }
                output.write(out.toByteArray())
                output.flush()
                exit.onExit(0)
            }
        }

        override fun destroy(channel: ChannelSession) = Unit
    }
}
