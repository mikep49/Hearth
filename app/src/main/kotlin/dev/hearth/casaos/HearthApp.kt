package dev.hearth.casaos

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.FileDownloader
import dev.hearth.casaos.data.SessionStore
import dev.hearth.casaos.ui.theme.ThemeStore

class AppContainer(app: Application) {
    val session = SessionStore(app)
    val client = CasaClient(session)
    val downloader = FileDownloader(app, client)
    val theme = ThemeStore(app)
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
