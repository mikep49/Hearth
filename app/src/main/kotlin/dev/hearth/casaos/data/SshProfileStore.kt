package dev.hearth.casaos.data

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What the Saved logins page shows of one server's SSH login: [address] is `user@host`, with the port if it
 * isn't 22. Never the password or the key itself. [server] is the CasaOS server's host it's saved under.
 */
data class SavedSshLogin(val server: String, val address: String, val hasPassword: Boolean, val hasKey: Boolean)

/** SSH sign-in details for one CasaOS server. */
data class SshProfile(
    val host: String,
    val port: Int = 22,
    val username: String = "",
    /** Only set when the user chose to remember it. */
    val password: String? = null,
    /** The key the user made or imported for this server, if any. */
    val key: SshKey? = null,
    /** Sign in with [key] rather than a password. */
    val useKey: Boolean = false,
) {
    /** What to sign in with when nobody is there to type, or null if nothing usable is saved. */
    val savedAuth: SshAuth?
        get() = if (useKey) key?.let(SshAuth::Key) else password?.let(SshAuth::Password)
}

/**
 * Remembers the SSH details per CasaOS server, and the host keys the user has trusted. A remembered
 * password or key is encrypted with a Keystore key like the CasaOS tokens, and is wiped on sign-out.
 */
class SshProfileStore(context: Context, private val box: Secrets = SecretBox()) : HostKeyStore {
    private val prefs = context.getSharedPreferences("ssh", Context.MODE_PRIVATE)
    private val _forgotten = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Each server [forget] was called for. */
    val forgotten: SharedFlow<String> = _forgotten.asSharedFlow()

    /** [server] is the CasaOS server's host; the SSH host defaults to it. */
    fun load(server: String): SshProfile {
        val key = loadKey(server)
        return SshProfile(
            host = prefs.getString("$server/host", null) ?: server,
            port = prefs.getInt("$server/port", 22),
            username = prefs.getString("$server/user", null).orEmpty(),
            password = prefs.getString("$server/password", null)?.let(box::decrypt),
            key = key,
            useKey = key != null && prefs.getBoolean("$server/use_key", false),
        )
    }

    private fun loadKey(server: String): SshKey? {
        val private = prefs.getString("$server/key", null)?.let(box::decrypt) ?: return null
        val public = prefs.getString("$server/key_public", null) ?: return null
        return SshKey(private, prefs.getString("$server/key_passphrase", null)?.let(box::decrypt), public)
    }

    /** Saves everything but the key, which [saveKey] stores as soon as the user adds it. */
    fun save(server: String, profile: SshProfile) {
        prefs.edit()
            .putString("$server/host", profile.host)
            .putInt("$server/port", profile.port)
            .putString("$server/user", profile.username)
            .putBoolean("$server/use_key", profile.useKey)
            .apply {
                if (profile.password != null) putString("$server/password", box.encrypt(profile.password))
                else remove("$server/password")
            }
            .apply()
    }

    /** Stores [key] for [server] and makes it the way to sign in, or removes the stored key when null. */
    fun saveKey(server: String, key: SshKey?) {
        prefs.edit().apply {
            if (key == null) {
                KEY_FIELDS.forEach { remove("$server/$it") }
            } else {
                putString("$server/key", box.encrypt(key.privateKey))
                putString("$server/key_public", key.publicKey)
                if (key.passphrase != null) putString("$server/key_passphrase", box.encrypt(key.passphrase))
                else remove("$server/key_passphrase")
            }
            putBoolean("$server/use_key", key != null)
        }.apply()
    }

    /** Forgets the remembered SSH password and key for [server] (the CasaOS server's host), e.g. when signing out of it. */
    fun forgetSecrets(server: String) {
        prefs.edit().apply {
            remove("$server/password")
            remove("$server/use_key")
            KEY_FIELDS.forEach { remove("$server/$it") }
        }.apply()
    }

    /** What's saved for signing in to each of [servers] over SSH, leaving out those with nothing saved. */
    fun saved(servers: List<String>): List<SavedSshLogin> = servers.mapNotNull { server ->
        val p = load(server)
        if (p.username.isBlank() && p.password == null && p.key == null) return@mapNotNull null
        val host = if (p.port == 22) p.host else "${p.host}:${p.port}"
        SavedSshLogin(
            server = server,
            address = if (p.username.isBlank()) host else "${p.username}@$host",
            hasPassword = p.password != null,
            hasKey = p.key != null,
        )
    }

    /**
     * Forgets everything saved for signing in to [server] over SSH: the address, the username, the password and
     * the key. The server's identity stays trusted. An open terminal hears of it through [forgotten].
     */
    fun forget(server: String) {
        prefs.edit().apply {
            (PROFILE_FIELDS + KEY_FIELDS).forEach { remove("$server/$it") }
        }.apply()
        _forgotten.tryEmit(server)
    }

    override fun hostKey(id: String): String? = prefs.getString("hostkey/$id", null)

    override fun trust(id: String, keyBase64: String) {
        prefs.edit().putString("hostkey/$id", keyBase64).apply()
    }

    private companion object {
        val PROFILE_FIELDS = listOf("host", "port", "user", "password", "use_key")
        val KEY_FIELDS = listOf("key", "key_public", "key_passphrase")
    }
}
