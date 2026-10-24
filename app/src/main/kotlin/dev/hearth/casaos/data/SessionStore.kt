package dev.hearth.casaos.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Session(val baseUrl: String, val username: String)

/** What [CasaClient] needs from session storage; lets the client be tested without Android. */
interface Credentials {
    val baseUrl: String?
    val accessToken: String?
    val refreshToken: String?
    fun signIn(baseUrl: String, username: String, token: TokenDto)
    fun updateTokens(token: TokenDto)
    fun signOut()
}

/**
 * Persists the server address and tokens. Tokens are encrypted with a Keystore key; the server
 * address and username are not secret. [session] drives top-level navigation (signed in vs. not).
 */
class SessionStore(context: Context, private val box: SecretBox = SecretBox()) : Credentials {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session.asStateFlow()

    override val baseUrl: String? get() = _session.value?.baseUrl

    @get:Synchronized
    override val accessToken: String? get() = prefs.getString(KEY_ACCESS, null)?.let(box::decrypt)

    @get:Synchronized
    override val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)?.let(box::decrypt)

    /** Last server address typed, kept after sign-out so the connect form can prefill it. */
    val lastServer: String get() = prefs.getString(KEY_LAST_SERVER, "") ?: ""

    @Synchronized
    override fun signIn(baseUrl: String, username: String, token: TokenDto) {
        prefs.edit()
            .putString(KEY_URL, baseUrl)
            .putString(KEY_USER, username)
            .putString(KEY_LAST_SERVER, baseUrl)
            .putString(KEY_ACCESS, box.encrypt(token.accessToken))
            .putString(KEY_REFRESH, box.encrypt(token.refreshToken))
            .apply()
        _session.value = Session(baseUrl, username)
    }

    @Synchronized
    override fun updateTokens(token: TokenDto) {
        prefs.edit()
            .putString(KEY_ACCESS, box.encrypt(token.accessToken))
            .putString(KEY_REFRESH, box.encrypt(token.refreshToken))
            .apply()
    }

    @Synchronized
    override fun signOut() {
        prefs.edit().remove(KEY_URL).remove(KEY_USER).remove(KEY_ACCESS).remove(KEY_REFRESH).apply()
        _session.value = null
    }

    private fun load(): Session? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val hasToken = prefs.getString(KEY_ACCESS, null)?.let(box::decrypt) != null
        // An undecryptable token (key invalidated) means we cannot act as this user: force a fresh sign-in.
        return if (hasToken) Session(url, prefs.getString(KEY_USER, "") ?: "") else null
    }

    private companion object {
        const val KEY_URL = "base_url"
        const val KEY_USER = "username"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_LAST_SERVER = "last_server"
    }
}
