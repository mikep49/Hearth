package dev.hearth.casaos

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.hearth.casaos.data.AppLock
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.FileDownloader
import dev.hearth.casaos.data.ImageRegistry
import dev.hearth.casaos.data.PhoneFiles
import dev.hearth.casaos.data.PrefsStore
import dev.hearth.casaos.data.ServerProfile
import dev.hearth.casaos.data.SessionStore
import dev.hearth.casaos.data.SshProfileStore
import dev.hearth.casaos.ui.theme.ThemeStore
import dev.hearth.casaos.widget.PrefsWidgetStorage
import dev.hearth.casaos.widget.ServerStatsWidgets
import dev.hearth.casaos.widget.WidgetServerChoices
import dev.hearth.casaos.widget.WidgetStatsStore
import dev.hearth.casaos.widget.WidgetTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

class AppContainer(private val app: Application) {
    val session = SessionStore(app)

    /** Shared by every server's client, so they share connections and threads. */
    private val http = OkHttpClient()

    /** Follows whichever server is being shown. Used to sign in, and for images. */
    val client = CasaClient(session, http)

    private val clients = ConcurrentHashMap<String, CasaClient>()

    /**
     * The client for one saved server. Screens use this rather than [client], so work still under way
     * after switching servers keeps talking to the server it started on.
     */
    fun clientFor(id: String): CasaClient = clients.getOrPut(id) { CasaClient(session.credentialsFor(id), http) }

    fun downloader(client: CasaClient) = FileDownloader(app, client)
    fun phoneFiles(client: CasaClient) = PhoneFiles(app, client)

    /** Lists image versions for the version picker. Separate from [client], so the CasaOS token never goes to a registry. */
    val registry = ImageRegistry()
    val theme = ThemeStore(app)
    val ssh = SshProfileStore(app)
    val prefs = PrefsStore(app)
    val lock = AppLock.create(app)
    val widgetStats = WidgetStatsStore(PrefsWidgetStorage(app), session::credentialsFor, ::clientFor)
    val widgetChoices = WidgetServerChoices(PrefsWidgetStorage(app, key = "server_choices"))

    /** The saved server a placed widget shows, if it shows one. */
    fun widgetServer(widgetId: Int): String? =
        WidgetTarget.of(widgetChoices.choices.value[widgetId], session.session.value, session.profiles.value).serverId

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // The lock counts time away from the whole app, so moving between its screens or rotating doesn't lock it.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = lock.onForeground()
            override fun onStop(owner: LifecycleOwner) = lock.onBackground()
        })
        // Signing out of a server removes its logins from the phone, SSH included (whatever triggered it).
        scope.launch {
            var before: List<ServerProfile>? = null // unknown until the first value
            session.profiles.collect { now ->
                val prev = before
                before = now
                if (prev == null) return@collect
                fun List<ServerProfile>.signedIn(id: String) = any { it.id == id && it.signedIn }
                val ended = prev.filter { it.signedIn && !now.signedIn(it.id) }
                if (ended.isNotEmpty()) forget(ended, now)
                if (now.any { it.signedIn && !prev.signedIn(it.id) }) refreshWidgets()
            }
        }
        // Widgets that follow the app show the server you switch to.
        scope.launch {
            session.session.drop(1).distinctUntilChanged().collect { if (it != null) refreshWidgets() }
        }
        // Widgets follow the app's theme, and so does their preview in the widget picker.
        scope.launch(Dispatchers.Default) {
            theme.settings.collectIndexed { i, settings ->
                if (i > 0) ServerStatsWidgets.redraw(app)
                runCatching { ServerStatsWidgets.publishPreview(app, settings) }
            }
        }
    }

    private fun forget(ended: List<ServerProfile>, now: List<ServerProfile>) {
        for (profile in ended) {
            val host = profile.baseUrl.toHttpUrlOrNull()?.host
            // Two accounts on one server share its SSH details; keep the password while the other is signed in.
            if (host != null && now.none { it.signedIn && it.baseUrl.toHttpUrlOrNull()?.host == host }) ssh.forgetPassword(host)
            widgetStats.clear(profile.id)
            if (now.none { it.id == profile.id }) clients.remove(profile.id)
        }
        PhoneFiles.clearCache(app)
        // Logins made inside the in-app browser go too. Its cookies can't be told apart reliably by server
        // (apps on other ports share the host's cookies), so all of them are cleared.
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        // Widgets showing that server say it's signed out.
        scope.launch(Dispatchers.Default) { ServerStatsWidgets.redraw(app) }
    }

    /** Called when Hearth goes to the background, so the widgets you're about to see are current. */
    fun refreshWidgets() {
        scope.launch(Dispatchers.Default) { ServerStatsWidgets.refresh(app) }
    }
}

class HearthApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.client.http })) }
            .build()
}
