package dev.hearth.casaos

import dev.hearth.casaos.data.HostKeyInfo
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import dev.hearth.casaos.data.HostKeyStore
import dev.hearth.casaos.data.SshAuth
import dev.hearth.casaos.data.SshException
import dev.hearth.casaos.data.SshKeyException
import dev.hearth.casaos.data.SshKeys
import dev.hearth.casaos.data.SshShell
import dev.hearth.casaos.data.hostKeyId
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
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.security.PublicKey
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs [SshShell] against a real SSH server in the test process. */
class SshShellTest {
    private lateinit var server: SshServer
    private val keys = MemoryKeys()
    private val host = "127.0.0.1"
    private val id get() = hostKeyId(host, server.port)

    /** The server's `authorized_keys`: one public key per line. */
    private val authorized = mutableListOf<String>()

    @Before fun start() {
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider() // in memory, fresh per test
            setPasswordAuthenticator { user, password, _ -> user == "casa" && password == "secret" }
            setPublickeyAuthenticator { user, key, _ -> user == "casa" && authorized.any { KeyUtils.compareKeys(publicKey(it), key) } }
            shellFactory = ShellFactory { UppercaseShell() }
            start()
        }
    }

    @After fun stop() = server.stop(true)

    private fun open(password: String = "secret") = open(SshAuth.Password(password))

    private fun open(auth: SshAuth) = runBlocking {
        SshShell.open(host, server.port, "casa", auth, keys, columns = 100, rows = 30)
    }

    private fun publicKey(line: String): PublicKey =
        AuthorizedKeyEntry.parseAuthorizedKeyEntry(line).resolvePublicKey(null, PublicKeyEntryResolver.FAILING)

    @Test fun `first connection asks to verify the fingerprint ssh-keygen would show`() {
        val e = expect<SshException.UnknownHost> { open() }
        val serverKey = server.keyPairProvider.loadKeys(null).first().public
        assertEquals(KeyUtils.getFingerPrint(serverKey), e.key.fingerprint)
        assertEquals(KeyUtils.getKeyType(serverKey), e.key.type)
        assertEquals(id, e.key.id)
        assertNull("nothing is trusted without the user's consent", keys.hostKey(id))
    }

    @Test fun `trusted server opens a shell with an xterm pty`() {
        val key = expect<SshException.UnknownHost> { open() }.key
        keys.trust(key.id, key.keyBase64)

        open().use { shell ->
            assertEquals("ready TERM=xterm-256color 100x30\r\n", readUntil(shell, "\n"))
            shell.write("hello".toByteArray())
            assertEquals("HELLO", readUntil(shell, "O"))
            shell.write("exit".toByteArray())
            assertEquals(-1, readEof(shell))
        }
    }

    @Test fun `wrong password is reported as an auth failure`() {
        trustServer()
        expect<SshException.Auth> { open(password = "nope") }
    }

    @Test fun `a key made on the phone signs in once the server knows it`() {
        trustServer()
        val key = SshKeys.generate()
        assertEquals("ssh-ed25519", key.type)
        assertTrue(key.publicKey.endsWith(" hearth"))
        assertEquals("what ssh-keygen -lf shows for it", KeyUtils.getFingerPrint(publicKey(key.publicKey)), key.fingerprint)

        val refused = expect<SshException.Auth> { open(SshAuth.Key(key)) }
        assertTrue("says where the key has to go", "authorized_keys" in refused.message.orEmpty())

        authorized += key.publicKey
        open(SshAuth.Key(key)).use { shell ->
            assertEquals("ready TERM=xterm-256color 100x30\r\n", readUntil(shell, "\n"))
        }
    }

    @Test fun `an imported key signs in, with its passphrase if it is locked`() {
        trustServer()
        for (type in listOf(KeyPair.ED25519, KeyPair.RSA, KeyPair.ECDSA)) {
            for (passphrase in listOf(null, "open sesame")) {
                val key = SshKeys.read(keyFile(type, passphrase), passphrase)
                assertEquals(passphrase, key.passphrase)
                authorized.clear()
                authorized += key.publicKey
                open(SshAuth.Key(key)).close()
            }
        }
    }

    @Test fun `a locked key asks for its passphrase and refuses a wrong one`() {
        val text = keyFile(KeyPair.ED25519, "open sesame")
        expect<SshKeyException.NeedsPassphrase> { SshKeys.read(text, null) }
        expect<SshKeyException.NeedsPassphrase> { SshKeys.read(text, "") }
        expect<SshKeyException.WrongPassphrase> { SshKeys.read(text, "open says me") }
        // Windows line endings and stray space around a pasted key don't matter.
        val key = SshKeys.read("  " + text.replace("\n", "\r\n") + "\n\n", "open sesame")
        assertEquals("ssh-ed25519", key.type)
    }

    @Test fun `a passphrase typed for a key without one is not kept`() {
        assertNull(SshKeys.read(keyFile(KeyPair.ED25519, null), "not needed").passphrase)
    }

    @Test fun `only private keys are taken`() {
        val key = SshKeys.generate()
        val public = expect<SshKeyException.NotAKey> { SshKeys.read(key.publicKey, null) }
        assertTrue("explains the mix-up", "public key" in public.message.orEmpty())
        expect<SshKeyException.NotAKey> { SshKeys.read("hello", null) }
        expect<SshKeyException.NotAKey> { SshKeys.read("", null) }
        expect<SshKeyException.NotAKey> { SshKeys.read("x".repeat(SshKeys.MAX_KEY_CHARS + 1), null) }
    }

    @Test fun `a key never prints its private half`() {
        val key = SshKeys.generate()
        val body = key.privateKey.lines()[1]
        assertTrue(body !in key.toString())
        assertTrue(body !in SshAuth.Key(key).toString())
        assertTrue("secret" !in SshAuth.Password("secret").toString())
    }

    /** A private key file as `ssh-keygen` writes it. */
    private fun keyFile(type: Int, passphrase: String?): String {
        val pair = when (type) {
            KeyPair.RSA -> KeyPair.genKeyPair(JSch(), type, 2048)
            KeyPair.ECDSA -> KeyPair.genKeyPair(JSch(), type, 256)
            else -> KeyPair.genKeyPair(JSch(), type)
        }
        val out = ByteArrayOutputStream()
        pair.writeOpenSSHv1PrivateKey(out, passphrase?.toByteArray())
        return out.toString(Charsets.UTF_8)
    }

    @Test fun `a different key than the trusted one is refused`() {
        keys.trust(id, HostKeyInfo.of(id, byteArrayOf(0, 0, 0, 7) + "ssh-rsa".toByteArray() + ByteArray(32)).keyBase64)
        expect<SshException.HostKeyChanged> { open() }
    }

    @Test fun `nothing listening is a network error`() {
        val freePort = ServerSocket(0).use { it.localPort }
        expect<SshException.Network> {
            runBlocking { SshShell.open(host, freePort, "casa", SshAuth.Password("secret"), keys, 80, 24) }
        }
    }

    private fun trustServer() {
        val key = expect<SshException.UnknownHost> { open() }.key
        keys.trust(key.id, key.keyBase64)
    }

    private fun readUntil(shell: SshShell, end: String): String {
        val out = StringBuilder()
        val buf = ByteArray(256)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!out.endsWith(end)) {
            check(System.nanoTime() < deadline) { "timed out, got: $out" }
            val n = shell.read(buf)
            check(n >= 0) { "closed early, got: $out" }
            out.append(String(buf, 0, n))
        }
        return out.toString()
    }

    private fun readEof(shell: SshShell): Int {
        val buf = ByteArray(256)
        while (true) {
            val n = shell.read(buf)
            if (n < 0) return n
        }
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

    companion object {
        /**
         * On Android, JSch does Ed25519 and X25519 through Bouncy Castle, since it takes the phone for Java 8.
         * On the JDK that runs these tests it would use the JDK's own, so point it at the classes the app uses.
         */
        @BeforeClass @JvmStatic fun useTheAppsCrypto() {
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        }
    }

    /** Announces the PTY it got, then echoes input in upper case until it sees "exit". */
    private class UppercaseShell : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var exit: ExitCallback

        override fun setInputStream(`in`: InputStream) { input = `in` }
        override fun setOutputStream(out: OutputStream) { output = out }
        override fun setErrorStream(err: OutputStream) = Unit
        override fun setExitCallback(callback: ExitCallback) { exit = callback }

        override fun start(channel: ChannelSession, env: Environment) {
            val term = env.env["TERM"]
            val size = "${env.env[Environment.ENV_COLUMNS]}x${env.env[Environment.ENV_LINES]}"
            thread(isDaemon = true) {
                output.write("ready TERM=$term $size\r\n".toByteArray())
                output.flush()
                val buf = ByteArray(256)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val s = String(buf, 0, n)
                    if ("exit" in s) break
                    output.write(s.uppercase().toByteArray())
                    output.flush()
                }
                exit.onExit(0)
            }
        }

        override fun destroy(channel: ChannelSession) = Unit
    }
}

class TerminalKeysTest {
    private val keys = dev.hearth.casaos.ui.terminal.TerminalKeys

    @Test fun `enter from the keyboard becomes a carriage return`() {
        assertEquals("ls\r", keys.encode("ls\n"))
    }

    @Test fun `ctrl turns letters into control codes`() {
        assertEquals("\u0003", keys.encode("c", ctrl = true))
        assertEquals("\u0003", keys.encode("C", ctrl = true))
        assertEquals("\u001b", keys.encode("[", ctrl = true))
        assertEquals("1", keys.encode("1", ctrl = true)) // no control code: sent as is
    }

    @Test fun `alt prefixes escape`() {
        assertEquals("\u001bb", keys.encode("b", alt = true))
    }

    @Test fun `host key type comes from the key blob`() {
        val blob = byteArrayOf(0, 0, 0, 11) + "ssh-ed25519".toByteArray() + ByteArray(36)
        val info = HostKeyInfo.of("h:22", blob)
        assertEquals("ssh-ed25519", info.type)
        assertTrue(info.fingerprint.startsWith("SHA256:"))
        assertTrue("no base64 padding, like ssh-keygen", !info.fingerprint.endsWith("="))
    }
}
