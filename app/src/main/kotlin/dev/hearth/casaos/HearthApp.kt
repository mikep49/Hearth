package dev.hearth.casaos

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.hearth.casaos.alerts.AlertNotifications
import dev.hearth.casaos.alerts.AlertSchedule
import dev.hearth.casaos.alerts.PrefsAlertStorage
import dev.hearth.casaos.data.AlertStore
import dev.hearth.casaos.data.AppLock
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.DocumentRef
import dev.hearth.casaos.data.FileDownloader
import dev.hearth.casaos.data.ImageRegistry
import dev.hearth.casaos.data.LanServerFinder
import dev.hearth.casaos.data.PhoneFiles
import dev.hearth.casaos.data.PrefsStore
import dev.hearth.casaos.data.SavedUploads
import dev.hearth.casaos.data.ServerProfile
import dev.hearth.casaos.data.SessionStore
import dev.hearth.casaos.data.SshProfileStore
import dev.hearth.casaos.data.WebLogins
import dev.hearth.casaos.data.Transfers
import dev.hearth.casaos.data.serverLabel
import dev.hearth.casaos.files.SaveUploadWorker
import dev.hearth.casaos.files.ServerDocumentsProvider
import dev.hearth.casaos.files.TransferNotifications
import dev.hearth.casaos.files.TransferService
import dev.hearth.casaos.ui.theme.ThemeStore
import dev.hearth.casaos.widget.PrefsWidgetStorage
import dev.hearth.casaos.widget.ServerStatsWidgets
import dev.hearth.casaos.widget.WidgetOptionsStore
import dev.hearth.casaos.widget.WidgetServerChoices
import dev.hearth.casaos.widget.WidgetStatsStore
import dev.hearth.casaos.widget.WidgetTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AppContainer(private val app: Application) {
    val session = SessionStore(app)

    /** Shared by every server's client, so they share connections and threads. */
    private val http = OkHttpClient()

    /** Follows whichever server is being shown. Used to sign in, and for images. */
    val client = CasaClient(session, http)

    /** Finds CasaOS servers on the local network for the sign-in page. */
    val finder = LanServerFinder(app, http)

    private val clients = ConcurrentHashMap<String, CasaClient>()

    /**
     * The client for one saved server. Screens use this rather than [client], so work still under way
     * after switching servers keeps talking to the server it started on.
     */
    fun clientFor(id: String): CasaClient = clients.getOrPut(id) {
        CasaClient(session.credentialsFor(id), http, onAppStopping = { app -> alerts.expectStop(id, app) })
    }

    fun downloader(client: CasaClient) = FileDownloader(app, client)
    fun phoneFiles(client: CasaClient) = PhoneFiles(app, client)

    /** Lists image versions for the version picker. Separate from [client], so the CasaOS token never goes to a registry. */
    val registry = ImageRegistry()
    val theme = ThemeStore(app)
    val ssh = SshProfileStore(app)
    val prefs = PrefsStore(app)
    val lock = AppLock.create(app)

    /** Locks of their own for the terminal and for apps opened in Hearth, for asking again there. */
    val terminalLock = AppLock.create(app, part = "terminal")
    val appsLock = AppLock.create(app, part = "apps")
    private val locks = listOf(lock, terminalLock, appsLock)
    val widgetStats = WidgetStatsStore(PrefsWidgetStorage(app), session::credentialsFor, ::clientFor)
    val widgetChoices = WidgetServerChoices(PrefsWidgetStorage(app, key = "server_choices"))
    /** What each placed widget shows, and its theme and text size. */
    val widgetOptions = WidgetOptionsStore(PrefsWidgetStorage(app, key = "widget_options"))
    val alerts = AlertStore(PrefsAlertStorage(app))

    /** Logins web apps keep in the in-app browser. */
    val webLogins = WebLogins(app)

    /** A server and tab to show, from a tapped alert. The app clears it once it's shown. */
    val openRequest = MutableStateFlow<OpenRequest?>(null)

    /** The saved server a placed widget shows, if it shows one. */
    fun widgetServer(widgetId: Int): String? =
        WidgetTarget.of(widgetChoices.choices.value[widgetId], session.session.value, session.profiles.value).serverId

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Downloads and uploads. They belong to the app, not a screen, so they outlive the screen that started them. */
    val transfers = Transfers(
        scope,
        keepAlive = { TransferService.start(app) },
        onOutcome = { TransferNotifications.result(app, it) },
    )

    /** What other apps save through Android Files, kept on the phone until the server has it. */
    val saves = SavedUploads(
        // Not in the cache, which Android empties when the phone runs low on space.
        dir = File(app.filesDir, "documents/uploads"),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        send = { ref, file -> file.inputStream().use { clientFor(ref.server).upload(ref.parent().path, ref.name, file.length(), it) } },
        keepOnPhone = { name, file -> SaveUploadWorker.keepOnPhone(app, name, file) },
        onUploaded = { ServerDocumentsProvider.uploaded(it) },
        onFailed = ::saveFailed,
        keepAlive = { SaveUploadWorker.schedule(app) },
    )

    init {
        // The locks count time away from the whole app, so moving between its screens or rotating doesn't lock it.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = locks.forEach { it.onForeground() }
            override fun onStop(owner: LifecycleOwner) = locks.forEach { it.onBackground() }
        })
        // Signing out of a server removes its logins from the phone, SSH included (whatever triggered it).
        scope.launch {
            var before: List<ServerProfile>? = null // unknown until the first value
            session.profiles.collect { now ->
                val prev = before
                before = now
                if (prev == null) return@collect
                ServerDocumentsProvider.serversChanged(app)
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
        // Servers show in Android's Files app while that's on, but never with the lock on: the Files app can't ask for it.
        scope.launch(Dispatchers.Default) {
            combine(prefs.showInFiles, lock.enabled) { show, locked -> show && !locked }
                .distinctUntilChanged()
                .collect { ServerDocumentsProvider.setShown(app, it) }
        }
        // Saves from other apps that hadn't reached the server when Android last ended Hearth.
        scope.launch(Dispatchers.IO) {
            // Earlier versions kept these in the cache with no note of where they went, so those can't be uploaded.
            runCatching { File(app.cacheDir, "documents/uploads").deleteRecursively() }
            saves.resume()
        }
        // Copies made for "Open with" and "Share" are kept for a day, then go even if nothing else is opened.
        scope.launch(Dispatchers.IO) { PhoneFiles.pruneCache(app) }
        // With logins not remembered, an app that was open when Android ended Hearth is signed out now.
        scope.launch { webLogins.clearLeftovers() }
        // The background check runs only while alerts are on.
        AlertNotifications.createChannels(app)
        scope.launch(Dispatchers.Default) {
            prefs.alerts.collect { AlertSchedule.sync(app, it) }
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
            // Two accounts on one server share its SSH details; keep its password and key while the other is signed in.
            if (host != null && now.none { it.signedIn && it.baseUrl.toHttpUrlOrNull()?.host == host }) ssh.forgetSecrets(host)
            widgetStats.clear(profile.id)
            alerts.clear(profile.id)
            AlertNotifications.cancelAll(app, profile.id)
            if (now.none { it.id == profile.id }) clients.remove(profile.id)
        }
        PhoneFiles.clearCache(app)
        ServerDocumentsProvider.clearCache(app)
        // Logins made inside the in-app browser go too, for every server: an app could be reached from more
        // than one, and the in-app browser doesn't know which server an address belongs to.
        webLogins.forgetAll()
        // Widgets showing that server say it's signed out.
        scope.launch(Dispatchers.Default) { ServerStatsWidgets.redraw(app) }
    }

    /** The app that saved [ref] has already been told it worked, so this is the only word the user gets. */
    private fun saveFailed(ref: DocumentRef, reason: String, onPhone: Boolean) {
        val server = session.profile(ref.server)?.let { serverLabel(it.baseUrl) } ?: "the server"
        val next = if (onPhone) "A copy is in Downloads/Hearth." else "Hearth is keeping it and will try again."
        scope.launch { Toast.makeText(app, "Couldn't save ${ref.name} to $server: $reason. $next", Toast.LENGTH_LONG).show() }
    }

    /** Whether Hearth is on screen, rather than in the background. */
    fun inFront(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** Called when Hearth goes to the background, so the widgets you're about to see are current. */
    fun refreshWidgets() {
        scope.launch(Dispatchers.Default) { ServerStatsWidgets.refresh(app) }
    }

    /** Redraws the widgets without fetching, e.g. after changing what one shows. */
    fun redrawWidgets() {
        scope.launch(Dispatchers.Default) { ServerStatsWidgets.redraw(app) }
    }
}

/** Where a tapped alert takes you: [server]'s Home or Apps. */
data class OpenRequest(val server: String, val apps: Boolean)

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
