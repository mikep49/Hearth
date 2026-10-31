package dev.hearth.casaos

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearth.casaos.ui.components.ClearOfCutout
import dev.hearth.casaos.ui.lock.LockGate
import dev.hearth.casaos.ui.theme.HearthTheme
import dev.hearth.casaos.ui.theme.isDark
import dev.hearth.casaos.ui.web.WebAppScreen

/**
 * An app's web UI on its own, opened from a home screen icon. Each address gets its own task, so it shows
 * up in Recents as a separate app, like an installed web app. It doesn't need a Hearth sign-in, but it is
 * behind Hearth's lock when that's on.
 */
class WebAppActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.dataString
        val scheme = intent.data?.scheme?.lowercase()
        if (url == null || (scheme != "http" && scheme != "https")) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME)?.ifBlank { null } ?: url.toUri().host.orEmpty()
        setRecentsLabel(name)

        enableEdgeToEdge()
        val container = (application as HearthApp).container
        setContent {
            val theme by container.theme.settings.collectAsStateWithLifecycle()
            val dark = theme.isDark()
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            HearthTheme(theme) {
                ClearOfCutout {
                    LockGate(container.lock) {
                        WebAppScreen(name, url, onClose = ::finishAndRemoveTask, Modifier.fillMaxSize(), standalone = true)
                    }
                }
            }
        }
    }

    private fun setRecentsLabel(name: String) {
        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityManager.TaskDescription.Builder().setLabel(name).build()
        } else {
            @Suppress("DEPRECATION")
            ActivityManager.TaskDescription(name)
        }
        setTaskDescription(description)
    }

    companion object {
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, name: String, url: String): Intent =
            Intent(Intent.ACTION_VIEW, url.toUri(), context, WebAppActivity::class.java).putExtra(EXTRA_NAME, name)
    }
}
