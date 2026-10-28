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
import dev.hearth.casaos.data.PrefsStore
import dev.hearth.casaos.data.SessionStore
import dev.hearth.casaos.data.SshProfileStore
import dev.hearth.casaos.ui.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(app: Application) {
    val session = SessionStore(app)
    val client = CasaClient(session)
    val downloader = FileDownloader(app, client)
    val theme = ThemeStore(app)
    val ssh = SshProfileStore(app)
    val prefs = PrefsStore(app)

    init {
        // Signing out removes the server's logins from the phone, SSH included (whatever triggered it).
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            var signedIn = false
            session.session.collect {
                if (it == null) {
                    ssh.forgetPasswords()
                    // Logins made inside the in-app browser go too. Skipped at a signed-out launch to avoid starting a WebView.
                    if (signedIn) {
                        CookieManager.getInstance().removeAllCookies(null)
                        WebStorage.getInstance().deleteAllData()
                    }
                }
                signedIn = it != null
            }
        }
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
