package dev.hearth.casaos.ui.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hearth.casaos.data.WebLogins
import dev.hearth.casaos.ui.components.EmptyState

/**
 * Shows an app's web UI inside Hearth. Back steps through the page history, then closes.
 *
 * Inside Hearth, the top bar has reload, open-in-browser and "Create app", which puts the app on the home
 * screen with [iconUrl] as its icon. A [standalone] app (opened from that icon) is just the page, like a
 * native app; the top bar is there when the app is opened through Hearth.
 *
 * Each app keeps its own logins, through [logins]. [strict] is the "Strict browsing" setting: no cookies for
 * other sites an app embeds, and no plain-HTTP parts on an HTTPS page.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebAppScreen(
    appName: String,
    url: String,
    logins: WebLogins,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    iconUrl: String? = null,
    standalone: Boolean = false,
    strict: Boolean = false,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val createApp = rememberCreateApp()
    // Survives rotation, which recreates the WebView, so the user stays on the page they were on.
    var currentUrl by rememberSaveable(url) { mutableStateOf(url) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Lets web apps (photo uploads etc.) use the system file picker.
    var pendingFiles by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFiles?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pendingFiles = null
    }
    val launchPicker by rememberUpdatedState { intent: Intent -> filePicker.launch(intent) }

    // Saves the login when the page leaves the screen, in case Android ends Hearth while it's away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, url) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) logins.flush(url)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // Without a top bar the content padding still keeps the page clear of the status bar.
            if (!standalone) Box {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") } },
                    title = {
                        Column {
                            Text(appName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                pageTitle?.takeIf { it.isNotBlank() && it != appName } ?: currentUrl.toUri().host.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { error = null; webView?.reload() }) { Icon(Icons.Rounded.Refresh, "Reload") }
                        // The app's address, not the current page, so it matches an app created from the Apps list.
                        IconButton(onClick = { createApp(appName, url, iconUrl) }) {
                            Icon(Icons.Rounded.InstallMobile, "Create app")
                        }
                        IconButton(onClick = { openExternally(context, currentUrl) }) { Icon(Icons.Rounded.OpenInBrowser, "Open in browser") }
                    },
                    // Tinted, unlike other pages: the web app's own page starts right under it.
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
                // Drawn over the bar's bottom edge rather than below it, so showing it doesn't resize the page.
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // First, since the profile (whose cookies the app gets) can't change once the view is in use.
                        logins.attach(this, appName, url, iconUrl)
                        // Without this the view defaults to wrap-content height, which WebView treats as a
                        // zero-height viewport: pages sized with height: 100% or vh collapse or lay out wrong.
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        // A page has no business reading the phone's files or other apps' content. Picking a
                        // file to upload doesn't go through either.
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        applyStrict(this, strict)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme?.lowercase()
                                if (scheme == "http" || scheme == "https") return false
                                // mailto:, intent:, app links and the like belong to other apps.
                                openExternally(ctx, request.url.toString())
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                error = null
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                currentUrl = url
                                canGoBack = view.canGoBack()
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                if (request.isForMainFrame) error = err.description?.toString()?.ifBlank { null } ?: "The page didn't load."
                            }

                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, err: SslError) {
                                handler.cancel()
                                error = "The app's HTTPS certificate isn't trusted by this phone."
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView, title: String?) {
                                pageTitle = title
                            }

                            override fun onShowFileChooser(
                                view: WebView,
                                callback: ValueCallback<Array<Uri>>,
                                params: FileChooserParams,
                            ): Boolean {
                                pendingFiles?.onReceiveValue(null)
                                pendingFiles = callback
                                try {
                                    launchPicker(params.createIntent())
                                } catch (_: ActivityNotFoundException) {
                                    callback.onReceiveValue(null)
                                    pendingFiles = null
                                }
                                return true
                            }
                        }
                        // The browser has its own cookies, so this may ask the user to sign in to the app again.
                        setDownloadListener { downloadUrl, _, _, _, _ -> openExternally(ctx, downloadUrl) }
                        loadUrl(currentUrl)
                        webView = this
                    }
                },
                // The setting can change while a home screen app is open in its own window.
                update = { view ->
                    if (view.tag != strict) applyStrict(view, strict)
                },
                onRelease = { view ->
                    if (webView === view) webView = null
                    view.stopLoading()
                    view.destroy()
                    // Rotating recreates the view, but the app is still open.
                    if (activity?.isChangingConfigurations == true) logins.flush(url) else logins.closed(url)
                },
            )

            error?.let { message ->
                EmptyState(
                    "Couldn't open $appName",
                    message,
                    // Opaque so the half-loaded page underneath doesn't show through.
                    Modifier.background(MaterialTheme.colorScheme.background),
                ) {
                    Button(onClick = { error = null; webView?.reload() }) { Text("Try again") }
                }
            }
        }
    }
}

/**
 * Not strict matches what the in-app browser always did: http images and media on https pages where WebView
 * judges it safe, and cookies for sites embedded in frames. Strict allows neither.
 */
private fun applyStrict(view: WebView, strict: Boolean) {
    view.settings.mixedContentMode =
        if (strict) WebSettings.MIXED_CONTENT_NEVER_ALLOW else WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, !strict)
    view.tag = strict
}

private fun openExternally(context: Context, url: String) {
    val intent = if (url.startsWith("intent:")) {
        // Links come from web content, so only let them reach apps that accept links from a browser.
        val parsed = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
        parsed.apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            component = null
            selector = null
        }
    } else {
        Intent(Intent.ACTION_VIEW, url.toUri())
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing on the phone can handle it; leave the user where they are.
    }
}
