package dev.hearth.casaos.data

import android.content.Context

/** SSH sign-in details for one CasaOS server. */
data class SshProfile(
    val host: String,
    val port: Int = 22,
    val username: String = "",
    /** Only set when the user chose to remember it. */
    val password: String? = null,
)

/**
 * Remembers the SSH details per CasaOS server, and the host keys the user has trusted. A remembered
 * password is encrypted with a Keystore key like the CasaOS tokens, and is wiped on sign-out.
 */
class SshProfileStore(context: Context, private val box: SecretBox = SecretBox()) : HostKeyStore {
    private val prefs = context.getSharedPreferences("ssh", Context.MODE_PRIVATE)

    /** [server] is the CasaOS server's host; the SSH host defaults to it. */
    fun load(server: String): SshProfile = SshProfile(
        host = prefs.getString("$server/host", null) ?: server,
        port = prefs.getInt("$server/port", 22),
        username = prefs.getString("$server/user", null).orEmpty(),
        password = prefs.getString("$server/password", null)?.let(box::decrypt),
    )

    fun save(server: String, profile: SshProfile) {
        prefs.edit()
            .putString("$server/host", profile.host)
            .putInt("$server/port", profile.port)
            .putString("$server/user", profile.username)
            .apply {
                if (profile.password != null) putString("$server/password", box.encrypt(profile.password))
                else remove("$server/password")
            }
            .apply()
    }

    /** Forgets the remembered SSH password for [server] (the CasaOS server's host), e.g. when signing out of it. */
    fun forgetPassword(server: String) {
        prefs.edit().remove("$server/password").apply()
    }

    override fun hostKey(id: String): String? = prefs.getString("hostkey/$id", null)

    override fun trust(id: String, keyBase64: String) {
        prefs.edit().putString("hostkey/$id", keyBase64).apply()
    }
}
