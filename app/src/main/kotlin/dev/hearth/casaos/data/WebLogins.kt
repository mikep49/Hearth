package dev.hearth.casaos.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import kotlin.coroutines.resume

/** An app that has a login saved in Hearth's in-app browser. */
data class SavedLogin(val origin: String, val name: String, val iconUrl: String?, val lastUsed: Long)

@Serializable
private data class Site(val name: String, val iconUrl: String? = null, val lastUrl: String, val lastUsed: Long)

/**
 * The logins web apps keep in Hearth's in-app browser: the cookies and site data an app saves when you
 * sign in to it. Hearth never sees or stores the password itself.
 *
 * Each app (its scheme, host and port) gets its own WebView profile, so its cookies are its own. Without
 * that, every app on a server would share one set, since cookies don't tell ports apart. Phones whose
 * WebView can't do profiles share one set, and there logins can only be removed all at once.
 *
 * WebView only works on the main thread, so call this there.
 */
class WebLogins(context: Context) {
    private val prefs = context.getSharedPreferences("web_logins", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val sitesSerializer = MapSerializer(String.serializer(), Site.serializer())
    private var sites: Map<String, Site> =
        prefs.getString(KEY_SITES, null)?.let { runCatching { json.decodeFromString(sitesSerializer, it) }.getOrNull() }.orEmpty()

    private val _remember = MutableStateFlow(prefs.getBoolean(KEY_REMEMBER, true))

    /** Whether apps stay signed in after they're closed. */
    val remember: StateFlow<Boolean> = _remember.asStateFlow()

    /** Whether each app has its own logins. Starts WebView, so ask only when it's about to be used anyway. */
    val perApp: Boolean by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }

    /** Turning it off signs you out of every app now, and of each app from then on when it closes. */
    fun setRemember(on: Boolean) {
        prefs.edit().putBoolean(KEY_REMEMBER, on).apply()
        _remember.value = on
        if (!on) forgetAll()
    }

    /** Gives [view] the logins of the app at [url], and notes the app for the list. Call before loading anything. */
    fun attach(view: WebView, name: String, url: String, iconUrl: String?) {
        val origin = originOf(url) ?: return
        if (perApp) {
            dropSharedLogins()
            ProfileStore.getInstance().getOrCreateProfile(profileName(origin))
            WebViewCompat.setProfile(view, profileName(origin))
        }
        // If Android ends Hearth while the app is open, its login is cleared the next time Hearth starts.
        if (!remember.value) prefs.edit().putBoolean(KEY_LEFTOVERS, true).apply()
        val old = sites[origin]
        val site = Site(
            name = name.ifBlank { old?.name ?: url.toHttpUrlOrNull()?.host.orEmpty() },
            iconUrl = iconUrl ?: old?.iconUrl,
            lastUrl = url,
            lastUsed = System.currentTimeMillis(),
        )
        saveSites(sites + (origin to site))
    }

    /**
     * Writes the cookies of the app at [url] to disk now. WebView otherwise only does it every 30 s or so,
     * and a login made just before Android ends Hearth would be lost.
     */
    fun flush(url: String) {
        val origin = originOf(url) ?: return
        cookiesOf(origin).flush()
    }

    /** Call when the app at [url] is closed for good: it keeps its login, or loses it if logins aren't remembered. */
    fun closed(url: String) {
        val origin = originOf(url) ?: return
        if (remember.value) cookiesOf(origin).flush() else clear(origin)
    }

    /** The apps with a login saved, most recently used first. Always empty when apps don't have their own logins. */
    suspend fun saved(): List<SavedLogin> {
        if (!perApp) return emptyList()
        val store = ProfileStore.getInstance()
        return sites.mapNotNull { (origin, site) ->
            val profile = store.getProfile(profileName(origin)) ?: return@mapNotNull null
            val cookies = profile.cookieManager
            val signedIn = !cookies.getCookie("$origin/").isNullOrBlank() ||
                !cookies.getCookie(site.lastUrl).isNullOrBlank() ||
                hasStorage(profile.webStorage)
            if (signedIn) SavedLogin(origin, site.name, site.iconUrl, site.lastUsed) else null
        }.sortedByDescending { it.lastUsed }
    }

    /** Signs you out of the app at [origin] (from [SavedLogin.origin]). */
    fun forget(origin: String) {
        clear(origin)
        saveSites(sites - origin)
    }

    /** Signs you out of every app. */
    fun forgetAll() {
        if (perApp) {
            val ours = ProfileStore.getInstance().allProfileNames.filter { it.startsWith(PROFILE_PREFIX) }
            ours.forEach(::dropProfile)
        }
        clearShared()
        saveSites(emptyMap())
        prefs.edit().putBoolean(KEY_LEFTOVERS, false).apply()
    }

    /** Clears logins left behind when Android ended Hearth with an app open while logins weren't remembered. */
    fun clearLeftovers() {
        if (!remember.value && prefs.getBoolean(KEY_LEFTOVERS, false)) forgetAll()
    }

    private fun cookiesOf(origin: String): CookieManager =
        if (perApp) ProfileStore.getInstance().getOrCreateProfile(profileName(origin)).cookieManager else CookieManager.getInstance()

    private fun clear(origin: String) {
        if (perApp) dropProfile(profileName(origin)) else clearShared()
    }

    private fun dropProfile(name: String) {
        val store = ProfileStore.getInstance()
        // A profile can't be deleted while a WebView still uses it (an app open in another window): empty it instead.
        val deleted = try {
            store.deleteProfile(name)
        } catch (_: IllegalStateException) {
            false
        }
        if (!deleted) store.getProfile(name)?.let {
            it.cookieManager.removeAllCookies(null)
            it.cookieManager.flush()
            it.webStorage.deleteAllData()
        }
    }

    private fun clearShared() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    /** Logins from before apps had their own are in the shared set, which nothing reads any more. */
    private fun dropSharedLogins() {
        if (prefs.getBoolean(KEY_SPLIT, false)) return
        clearShared()
        prefs.edit().putBoolean(KEY_SPLIT, true).apply()
    }

    private fun saveSites(value: Map<String, Site>) {
        sites = value
        prefs.edit().putString(KEY_SITES, json.encodeToString(sitesSerializer, value)).apply()
    }

    private suspend fun hasStorage(storage: WebStorage): Boolean = suspendCancellableCoroutine { cont ->
        storage.getOrigins { origins -> if (cont.isActive) cont.resume(!origins.isNullOrEmpty()) }
    }

    private companion object {
        const val KEY_SITES = "sites"
        const val KEY_REMEMBER = "remember"
        const val KEY_LEFTOVERS = "leftovers"
        const val KEY_SPLIT = "split_profiles"
    }
}

private const val PROFILE_PREFIX = "app-"

/** The part of [url] that identifies an app: scheme, host and port, like `http://192.168.1.20:8282`. */
fun originOf(url: String): String? =
    url.toHttpUrlOrNull()?.newBuilder()
        ?.username("")?.password("")?.encodedPath("/")?.query(null)?.fragment(null)
        ?.build()?.toString()?.removeSuffix("/")

/** A WebView profile name for [origin]. Hashed, so it's a plain name whatever the address holds. */
internal fun profileName(origin: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray())
    return PROFILE_PREFIX + hash.take(12).joinToString("") { "%02x".format(it) }
}
