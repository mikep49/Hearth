package dev.hearth.casaos

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebStorage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.FileDownloader
import dev.hearth.casaos.data.ImageRegistry
import dev.hearth.casaos.data.PhoneFiles
import dev.hearth.casaos.data.PrefsStore
import dev.hearth.casaos.data.SessionStore
import dev.hearth.casaos.data.SshProfileStore
import dev.hearth.casaos.ui.theme.ThemeStore
import dev.hearth.casaos.widget.PrefsWidgetStorage
import dev.hearth.casaos.widget.ServerStatsWidgets
import dev.hearth.casaos.widget.WidgetStatsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.launch

class AppContainer(private val app: Application) {
    val session = SessionStore(app)
    val client = CasaClient(session)
    /** Lists image versions for the version picker. Separate from [client], so the CasaOS token never goes to a registry. */
    val registry = ImageRegistry()
    val downloader = FileDownloader(app, client)
    val phoneFiles = PhoneFiles(app, client)
    val theme = ThemeStore(app)
    val ssh = SshProfileStore(app)
    val prefs = PrefsStore(app)
    val widgetStats = WidgetStatsStore(PrefsWidgetStorage(app), client, session)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // Signing out removes the server's logins from the phone, SSH included (whatever triggered it).
        scope.launch {
            var signedIn: Boolean? = null // unknown until the first value
            session.session.collect {
                if (it == null) {
                    ssh.forgetPasswords()
                    phoneFiles.clear()
                    // Logins made inside the in-app browser go too. Skipped at a signed-out launch to avoid starting a WebView.
                    if (signedIn == true) {
                        CookieManager.getInstance().removeAllCookies(null)
                        WebStorage.getInstance().deleteAllData()
                        // Home screen widgets stop showing the server.
                        widgetStats.clear()
                        launch(Dispatchers.Default) { ServerStatsWidgets.redraw(app) }
                    }
                } else if (signedIn == false) {
                    launch(Dispatchers.Default) { ServerStatsWidgets.refresh(app) }
                }
                signedIn = it != null
            }
        }
        // Widgets follow the app's theme, and so does their preview in the widget picker.
        scope.launch(Dispatchers.Default) {
            theme.settings.collectIndexed { i, settings ->
                if (i > 0) ServerStatsWidgets.redraw(app)
                runCatching { ServerStatsWidgets.publishPreview(app, settings) }
            }
        }
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
