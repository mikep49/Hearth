package dev.hearth.casaos.data

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A private key to sign in with, as the text OpenSSH or PuTTY wrote it, and the passphrase that unlocks it if
 * it has one. [publicKey] is its public half as a line for the server's `authorized_keys`: safe to show and copy.
 */
data class SshKey(val privateKey: String, val passphrase: String?, val publicKey: String) {
    /** The algorithm, e.g. `ssh-ed25519`. */
    val type: String get() = publicKey.substringBefore(' ')

    /** In the format `ssh-keygen -lf` prints, or empty if [publicKey] isn't a key line. */
    val fingerprint: String
        get() = runCatching { publicKeyFingerprint(Base64.getDecoder().decode(publicKey.split(' ')[1])) }.getOrDefault("")

    override fun toString() = "SshKey($type $fingerprint)" // never the private half, if this is ever logged
}

sealed class SshKeyException(message: String) : Exception(message) {
    class NotAKey(message: String) : SshKeyException(message)
    class NeedsPassphrase : SshKeyException("This key is locked. Enter its passphrase.")
    class WrongPassphrase : SshKeyException("That passphrase doesn't unlock this key.")
}

/** Reads and makes SSH keys. Both can take a moment (unlocking a key is slow on purpose): call off the main thread. */
object SshKeys {
    /** A key file is a few kilobytes at most; anything much larger isn't one. */
    const val MAX_KEY_CHARS = 64 * 1024

    /**
     * Checks that [text] is a private key in a format OpenSSH or PuTTY writes and that [passphrase] unlocks it.
     * Throws [SshKeyException].
     */
    fun read(text: String, passphrase: String?): SshKey {
        val normalized = text.replace("\r\n", "\n").trim() + "\n"
        if (PUBLIC_KEY_LINE.containsMatchIn(normalized)) {
            throw SshKeyException.NotAKey("That's a public key. Hearth needs the private key: the file without .pub at the end.")
        }
        if (normalized.length > MAX_KEY_CHARS) throw notAKey()
        val pair = try {
            KeyPair.load(JSch(), normalized.toByteArray(), null)
        } catch (e: Exception) {
            throw notAKey()
        }
        try {
            val locked = pair.isEncrypted
            if (locked) {
                if (passphrase.isNullOrEmpty()) throw SshKeyException.NeedsPassphrase()
                val unlocked = runCatching { pair.decrypt(passphrase.toByteArray()) }.getOrDefault(false)
                if (!unlocked) throw SshKeyException.WrongPassphrase()
            }
            val blob = pair.publicKeyBlob ?: throw notAKey()
            return SshKey(
                privateKey = normalized,
                passphrase = passphrase.takeIf { locked }, // a passphrase typed for a key that has none is dropped
                publicKey = publicKeyLine(blob, pair.publicKeyComment),
            )
        } finally {
            pair.dispose()
        }
    }

    /** Makes a new Ed25519 key, the kind `ssh-keygen` makes by default. [comment] labels it in `authorized_keys`. */
    fun generate(comment: String = "hearth"): SshKey {
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        try {
            val out = ByteArrayOutputStream()
            pair.writeOpenSSHv1PrivateKey(out, null)
            // Not out.toString(charset), which Android only has from 13.
            return SshKey(out.toByteArray().toString(Charsets.UTF_8), passphrase = null, publicKey = publicKeyLine(pair.publicKeyBlob, comment))
        } finally {
            pair.dispose()
        }
    }

    private fun publicKeyLine(blob: ByteArray, comment: String?): String {
        val line = publicKeyType(blob) + " " + Base64.getEncoder().encodeToString(blob)
        // A comment is free text from the key file: keep it to one line.
        val label = comment.orEmpty().lineSequence().firstOrNull().orEmpty().trim()
        return if (label.isEmpty()) line else "$line $label"
    }

    private fun notAKey() = SshKeyException.NotAKey(
        "Hearth can't read that as a private key. It reads the key files OpenSSH and PuTTY make.",
    )

    private val PUBLIC_KEY_LINE = Regex("""^(ssh-(rsa|dss|ed25519|ed448)|ecdsa-sha2-\S+|sk-\S+) AAAA""")
}
