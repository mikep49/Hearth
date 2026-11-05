package dev.hearth.casaos.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearth.casaos.HearthApp
import dev.hearth.casaos.data.ServerProfile
import dev.hearth.casaos.ui.components.ClearOfCutout
import dev.hearth.casaos.ui.components.ListGroup
import dev.hearth.casaos.ui.components.ListRow
import dev.hearth.casaos.ui.components.readableWidth
import dev.hearth.casaos.ui.servers.ServerRow
import dev.hearth.casaos.ui.theme.HearthTheme
import dev.hearth.casaos.ui.theme.isDark

/**
 * Picks which saved server a stats widget shows. Opened when placing a widget (Android 11 and older),
 * from the widget's settings (Android 12+), or by tapping the server name on the widget.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Other apps can start this screen, so only act on Hearth's own widgets.
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (provider != ComponentName(this, ServerStatsWidgetReceiver::class.java)) {
            finish()
            return
        }
        // Leaving without picking keeps the widget, showing whichever server Hearth has open.
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val container = (application as HearthApp).container
        // Nothing to pick between while one server (or none) is saved, so placing a widget skips this.
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE && container.session.profiles.value.size <= 1) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            val theme by container.theme.settings.collectAsStateWithLifecycle()
            val dark = theme.isDark()
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            HearthTheme(theme) { ClearOfCutout {
                val profiles by container.session.profiles.collectAsStateWithLifecycle()
                val choices by container.widgetChoices.choices.collectAsStateWithLifecycle()
                WidgetServerPicker(profiles, choices[widgetId]) { server ->
                    container.widgetChoices.set(widgetId, server)
                    container.refreshWidgets()
                    finish()
                }
            } }
        }
    }
}

@Composable
private fun WidgetServerPicker(profiles: List<ServerProfile>, current: String?, onPick: (String?) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).readableWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Widget server", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineMedium)
            Text(
                "Pick the server this widget shows.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ListGroup {
                val selected = current == null
                ListRow(
                    "Same as the app",
                    subtitle = "Shows whichever server is open in Hearth",
                    leading = {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (selected) Icons.Rounded.Check else Icons.Rounded.Sync,
                                null,
                                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onPick(null) },
                )
                profiles.forEach { profile ->
                    ServerRow(profile, selected = profile.id == current, onClick = { onPick(profile.id) })
                }
            }
            if (profiles.isEmpty()) {
                Text(
                    "Sign in to a server in Hearth first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
