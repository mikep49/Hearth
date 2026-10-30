package dev.hearth.casaos.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** The account Hearth is showing: one user on one CasaOS server. */
data class Session(val id: String, val baseUrl: String, val username: String) {
    /** "192.168.1.10" or "casaos.local:8080", for headings. */
    val label: String get() = serverLabel(baseUrl)
}

/** A saved server. [signedIn] is false once its login ended or expired; signing in again picks it back up. */
data class ServerProfile(val id: String, val baseUrl: String, val username: String, val signedIn: Boolean) {
    val session: Session get() = Session(id, baseUrl, username)
    val label: String get() = serverLabel(baseUrl)
    /** The address as typed into the sign-in form: "http://" is the default there, so it's left off. */
    val address: String get() = baseUrl.removePrefix("http://")
}

fun serverLabel(baseUrl: String): String = baseUrl.substringAfter("://").trimEnd('/')

/** What [CasaClient] needs from session storage; lets the client be tested without Android. */
interface Credentials {
    val baseUrl: String?
    val accessToken: String?
    val refreshToken: String?
    fun signIn(baseUrl: String, username: String, token: TokenDto)
    fun updateTokens(token: TokenDto)
    fun signOut()
}

/** Encrypts tokens at rest. [SecretBox] on the phone; tests use a stand-in. */
interface Secrets {
    fun encrypt(plain: String): String
    fun decrypt(blob: String): String?
}

/** The few string settings [SessionStore] keeps. A null value in [edit] removes the key. */
interface KeyValueStore {
    fun get(key: String): String?
    fun edit(changes: Map<String, String?>)
}

class SharedPrefsKeyValue(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun edit(changes: Map<String, String?>) {
        prefs.edit().apply { changes.forEach { (k, v) -> if (v == null) remove(k) else putString(k, v) } }.apply()
    }
}

/**
 * Keeps every server the user has signed in to, and which one the app shows ([session]). Tokens are
 * encrypted with a Keystore key; addresses and usernames are not secret.
 *
 * As [Credentials] it stands for the server being shown: that's what signing in on the connect screen uses.
 * [credentialsFor] gives the login of one particular server, for screens and widgets tied to it.
 */
class SessionStore(private val prefs: KeyValueStore, private val box: Secrets) : Credentials {
    constructor(context: Context) : this(
        SharedPrefsKeyValue(context.getSharedPreferences("session", Context.MODE_PRIVATE)),
        SecretBox(),
    )

    @Serializable
    private data class Stored(val id: String, val baseUrl: String, val username: String)

    private var stored: List<Stored>
    private var activeId: String?

    // Every request reads the access token, including each app icon, and decrypting goes through the
    // Keystore. Doing that per request made image loads queue up behind each other, so they're kept decrypted.
    // A server with no entry here is signed out (that includes a token that no longer decrypts, e.g. after a restore).
    private val access = HashMap<String, String>()

    init {
        migrateSingleLogin()
        stored = prefs.get(KEY_PROFILES)
            ?.let { runCatching { json.decodeFromString(ListSerializer(Stored.serializer()), it) }.getOrNull() }
            .orEmpty()
        activeId = prefs.get(KEY_ACTIVE)?.takeIf { id -> stored.any { it.id == id } }
        for (s in stored) prefs.get(accessKey(s.id))?.let(box::decrypt)?.let { access[s.id] = it }
    }

    private val _profiles = MutableStateFlow(profileList())
    /** Saved servers, in the order they were first added. */
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    private val _session = MutableStateFlow(activeSession())
    /** The server being shown; null when it's signed out (or none is saved), which shows the connect screen. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    /**
     * The server last shown, kept after it signs out so the connect screen can offer to sign in again.
     * Null once it's removed.
     */
    val lastProfile: ServerProfile? get() = _profiles.value.firstOrNull { it.id == activeId }

    @Synchronized
    fun profile(id: String): ServerProfile? = _profiles.value.firstOrNull { it.id == id }

    // region Credentials, for the server being shown

    override val baseUrl: String? get() = _session.value?.baseUrl

    @get:Synchronized
    override val accessToken: String? get() = activeId?.let(access::get)

    @get:Synchronized
    override val refreshToken: String? get() = activeId?.let(::refreshTokenOf)

    /** Adds the server, or signs back in to it when it's saved already, and shows it. */
    @Synchronized
    override fun signIn(baseUrl: String, username: String, token: TokenDto) {
        val existing = stored.firstOrNull { it.baseUrl == baseUrl && it.username.equals(username, ignoreCase = true) }
        val profile = existing?.copy(username = username) ?: Stored(UUID.randomUUID().toString(), baseUrl, username)
        stored = if (existing == null) stored + profile else stored.map { if (it.id == profile.id) profile else it }
        activeId = profile.id
        access[profile.id] = token.accessToken
        prefs.edit(
            mapOf(
                KEY_PROFILES to json.encodeToString(ListSerializer(Stored.serializer()), stored),
                KEY_ACTIVE to profile.id,
                accessKey(profile.id) to box.encrypt(token.accessToken),
                refreshKey(profile.id) to box.encrypt(token.refreshToken),
            ),
        )
        publish()
    }

    @Synchronized
    override fun updateTokens(token: TokenDto) {
        activeId?.let { updateTokens(it, token) }
    }

    /** Ends the login of the server being shown. It stays in the list, so signing in again is quick. */
    @Synchronized
    override fun signOut() {
        activeId?.let(::signOut)
    }

    // endregion

    /** Shows another saved server. Does nothing if it's signed out: that needs a fresh sign-in. */
    @Synchronized
    fun switchTo(id: String) {
        if (id == activeId || id !in access) return
        activeId = id
        prefs.edit(mapOf(KEY_ACTIVE to id))
        publish()
    }

    /** Ends [id]'s login and removes its tokens from the phone; the server stays in the list. */
    @Synchronized
    fun signOut(id: String) {
        if (access.remove(id) == null && prefs.get(refreshKey(id)) == null) return
        prefs.edit(mapOf(accessKey(id) to null, refreshKey(id) to null))
        publish()
    }

    /** Forgets [id] entirely: its address, username and login. */
    @Synchronized
    fun remove(id: String) {
        if (stored.none { it.id == id }) return
        stored = stored.filterNot { it.id == id }
        access.remove(id)
        val changes = mutableMapOf<String, String?>(
            KEY_PROFILES to json.encodeToString(ListSerializer(Stored.serializer()), stored),
            accessKey(id) to null,
            refreshKey(id) to null,
        )
        if (activeId == id) {
            activeId = null
            changes[KEY_ACTIVE] = null
        }
        prefs.edit(changes)
        publish()
    }

    /** The login of one saved server, whichever server is being shown. */
    fun credentialsFor(id: String): Credentials = ProfileCredentials(id)

    private inner class ProfileCredentials(private val id: String) : Credentials {
        override val baseUrl: String? get() = profile(id)?.takeIf { it.signedIn }?.baseUrl
        override val accessToken: String? get() = synchronized(this@SessionStore) { access[id] }
        override val refreshToken: String? get() = synchronized(this@SessionStore) { refreshTokenOf(id) }
        override fun signIn(baseUrl: String, username: String, token: TokenDto) = this@SessionStore.signIn(baseUrl, username, token)
        override fun updateTokens(token: TokenDto) = synchronized(this@SessionStore) { updateTokens(id, token) }
        override fun signOut() = this@SessionStore.signOut(id)
    }

    private fun updateTokens(id: String, token: TokenDto) {
        // Signed out (or removed) while the refresh was under way: don't bring the login back.
        if (id !in access) return
        access[id] = token.accessToken
        prefs.edit(mapOf(accessKey(id) to box.encrypt(token.accessToken), refreshKey(id) to box.encrypt(token.refreshToken)))
    }

    private fun refreshTokenOf(id: String): String? =
        if (id in access) prefs.get(refreshKey(id))?.let(box::decrypt) else null

    private fun profileList() = stored.map { ServerProfile(it.id, it.baseUrl, it.username, signedIn = it.id in access) }

    private fun activeSession(): Session? = activeId?.takeIf { it in access }?.let { id -> stored.first { it.id == id } }
        ?.let { Session(it.id, it.baseUrl, it.username) }

    private fun publish() {
        _profiles.value = profileList()
        _session.value = activeSession()
    }

    /** Hearth used to keep one login under fixed keys; it becomes the first saved server. */
    private fun migrateSingleLogin() {
        val url = prefs.get(OLD_URL) ?: return
        if (prefs.get(KEY_PROFILES) == null) {
            val id = UUID.randomUUID().toString()
            prefs.edit(
                mapOf(
                    KEY_PROFILES to json.encodeToString(ListSerializer(Stored.serializer()), listOf(Stored(id, url, prefs.get(OLD_USER).orEmpty()))),
                    KEY_ACTIVE to id,
                    // Copied still encrypted, so this needs no Keystore work.
                    accessKey(id) to prefs.get(OLD_ACCESS),
                    refreshKey(id) to prefs.get(OLD_REFRESH),
                ),
            )
        }
        prefs.edit(mapOf(OLD_URL to null, OLD_USER to null, OLD_ACCESS to null, OLD_REFRESH to null, OLD_LAST_SERVER to null))
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE = "active"
        fun accessKey(id: String) = "access/$id"
        fun refreshKey(id: String) = "refresh/$id"

        // The single login from before servers could be saved.
        const val OLD_URL = "base_url"
        const val OLD_USER = "username"
        const val OLD_ACCESS = "access_token"
        const val OLD_REFRESH = "refresh_token"
        const val OLD_LAST_SERVER = "last_server"
    }
}
