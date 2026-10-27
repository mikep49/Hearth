package dev.hearth.casaos.data

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/** Trusted SSH host keys, keyed by [HostKeyInfo.id] ("host:port"). Values are the base64 key blob. */
interface HostKeyStore {
    fun hostKey(id: String): String?
    fun trust(id: String, keyBase64: String)
}

/** A host key as the server presented it, ready to show the user. */
data class HostKeyInfo(val id: String, val type: String, val fingerprint: String, val keyBase64: String) {
    companion object {
        fun of(id: String, key: ByteArray) = HostKeyInfo(
            id = id,
            type = keyType(key),
            // Same format as `ssh-keygen -lf`, so the user can compare it with what the server prints.
            fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key)),
            keyBase64 = Base64.getEncoder().encodeToString(key),
        )

        /** The key blob starts with its algorithm name as an SSH string (uint32 length + bytes). */
        private fun keyType(key: ByteArray): String {
            if (key.size < 4) return "unknown"
            val len = ((key[0].toInt() and 0xff) shl 24) or ((key[1].toInt() and 0xff) shl 16) or
                ((key[2].toInt() and 0xff) shl 8) or (key[3].toInt() and 0xff)
            if (len <= 0 || len > key.size - 4 || len > 64) return "unknown"
            return String(key, 4, len, Charsets.US_ASCII)
        }
    }
}

sealed class SshException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** First connection to this server: the user has to confirm the fingerprint. */
    class UnknownHost(val key: HostKeyInfo) : SshException("This server hasn't been verified yet")

    /** The server's key differs from the one trusted before: either it was reinstalled or someone is intercepting. */
    class HostKeyChanged(val key: HostKeyInfo) : SshException("The server's identity has changed")
    class Auth : SshException("Wrong username or password, or the server doesn't allow password sign-in")
    class Network(message: String, cause: Throwable? = null) : SshException(message, cause)
}

fun hostKeyId(host: String, port: Int) = "$host:$port"

/**
 * Trust on first use, like OpenSSH's known_hosts: an unknown key is rejected until the user accepts it,
 * and a key that differs from the stored one is always rejected.
 */
internal class TofuHostKeys(private val id: String, private val store: HostKeyStore) : HostKeyRepository {
    @Volatile var presented: HostKeyInfo? = null
        private set
    @Volatile var lastResult: Int = -1
        private set

    override fun check(host: String?, key: ByteArray): Int {
        val info = HostKeyInfo.of(id, key)
        presented = info
        val known = store.hostKey(id)
        lastResult = when {
            known == null -> HostKeyRepository.NOT_INCLUDED
            known == info.keyBase64 -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
        return lastResult
    }

    // Returning the trusted key lets JSch negotiate that key's algorithm, so the same key is presented each time.
    override fun getHostKey(host: String?, type: String?): Array<HostKey> {
        val known = store.hostKey(id) ?: return emptyArray()
        return runCatching { arrayOf(HostKey(host ?: id, Base64.getDecoder().decode(known))) }.getOrElse { emptyArray() }
    }

    override fun getHostKey(): Array<HostKey> = getHostKey(null, null)
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit // trusting happens only through the user's explicit choice
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "hearth"
}

/** An interactive shell on the server with a pseudo-terminal attached. Construct before connecting [channel]. */
class SshShell private constructor(private val session: Session, private val channel: ChannelShell) : Closeable {
    // JSch requires the streams to be taken before the channel connects.
    private val input: InputStream = channel.inputStream
    private val output: OutputStream = channel.outputStream

    val isOpen: Boolean get() = session.isConnected && !channel.isClosed

    /** Blocking: call off the main thread. Returns -1 once the shell exits. */
    fun read(buffer: ByteArray): Int = input.read(buffer)

    /** Blocking: call off the main thread. */
    fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    /** Blocking: call off the main thread. */
    fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) = channel.setPtySize(columns, rows, widthPx, heightPx)

    override fun close() {
        channel.disconnect()
        session.disconnect()
    }

    companion object {
        private const val TIMEOUT_MS = 12_000

        /**
         * Signs in with a password and opens a shell. Throws [SshException]: [SshException.UnknownHost] and
         * [SshException.HostKeyChanged] carry the presented key so the caller can ask the user and retry.
         */
        suspend fun open(
            host: String,
            port: Int,
            username: String,
            password: String,
            hostKeys: HostKeyStore,
            columns: Int,
            rows: Int,
        ): SshShell = withContext(Dispatchers.IO) {
            val keys = TofuHostKeys(hostKeyId(host, port), hostKeys)
            val jsch = JSch().apply { hostKeyRepository = keys }
            val session = try {
                jsch.getSession(username, host, port)
            } catch (e: JSchException) {
                throw SshException.Network(e.message ?: "Invalid address", e)
            }
            session.setPassword(password)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            session.serverAliveInterval = 30_000 // keeps NAT/Wi-Fi idle timeouts from silently dropping the shell
            // JSch's connect blocks and ignores interruption, so cancelling the caller has to close the socket.
            val cancel = currentCoroutineContext().job.invokeOnCompletion { if (it != null) session.disconnect() }
            try {
                session.connect(TIMEOUT_MS)
                val channel = session.openChannel("shell") as ChannelShell
                channel.setPtyType("xterm-256color", columns, rows, 0, 0)
                val shell = SshShell(session, channel)
                channel.connect(TIMEOUT_MS)
                shell
            } catch (e: JSchException) {
                session.disconnect()
                throw mapError(e, keys)
            } finally {
                cancel.dispose()
            }
        }

        private fun mapError(e: JSchException, keys: TofuHostKeys): SshException {
            val presented = keys.presented
            if (presented != null && keys.lastResult == HostKeyRepository.NOT_INCLUDED) return SshException.UnknownHost(presented)
            if (presented != null && keys.lastResult == HostKeyRepository.CHANGED) return SshException.HostKeyChanged(presented)
            val msg = e.message.orEmpty()
            if (msg.startsWith("Auth")) return SshException.Auth()
            return SshException.Network(
                when (e.cause) {
                    is java.net.UnknownHostException -> "Couldn't find that server on the network"
                    is java.net.ConnectException -> "Couldn't connect. Is SSH enabled on the server, and is the port right?"
                    is java.net.SocketTimeoutException -> "The server took too long to respond"
                    else -> if ("timeout" in msg) "The server took too long to respond" else msg.ifBlank { "Connection failed" }
                },
                e,
            )
        }
    }
}
