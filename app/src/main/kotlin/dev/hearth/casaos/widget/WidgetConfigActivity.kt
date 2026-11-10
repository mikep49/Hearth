package dev.hearth.casaos.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearth.casaos.HearthApp
import dev.hearth.casaos.data.ServerProfile
import dev.hearth.casaos.ui.components.ClearOfCutout
import dev.hearth.casaos.ui.components.GroupFootnote
import dev.hearth.casaos.ui.components.GroupRowShape
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.ListGroup
import dev.hearth.casaos.ui.components.ListRow
import dev.hearth.casaos.ui.components.SectionHeader
import dev.hearth.casaos.ui.components.SwitchRow
import dev.hearth.casaos.ui.components.readableWidth
import dev.hearth.casaos.ui.servers.ServerRow
import dev.hearth.casaos.ui.theme.HearthTheme
import dev.hearth.casaos.ui.theme.isDark

/**
 * A stats widget's settings: what it shows, its theme and text size, and which saved server it follows.
 * Opened when placing a widget (Android 11 and older), from the widget's settings (Android 12+), by tapping
 * the server name on the widget, or from Hearth's Settings. Changes show on the widget right away.
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
        // Leaving without changing anything keeps the widget, with the defaults.
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val container = (application as HearthApp).container

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
                val allOptions by container.widgetOptions.options.collectAsStateWithLifecycle()
                WidgetSettings(
                    options = allOptions[widgetId] ?: WidgetOptions(),
                    onOptionsChange = {
                        container.widgetOptions.set(widgetId, it)
                        container.redrawWidgets()
                    },
                    profiles = profiles,
                    server = choices[widgetId],
                    onServerChange = {
                        container.widgetChoices.set(widgetId, it)
                        container.refreshWidgets()
                    },
                    onDone = ::finish,
                )
            } }
        }
    }

    companion object {
        /** Opens [widgetId]'s settings from inside the app. */
        fun intent(context: Context, widgetId: Int): Intent = Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .setData(Uri.parse("hearth://widget/$widgetId"))

        /** The stats widgets on the home screen, by app widget id. */
        fun placed(context: Context): List<Int> = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ServerStatsWidgetReceiver::class.java))
            .sorted()
    }
}

private val WidgetItem.icon: ImageVector
    get() = when (this) {
        WidgetItem.Status -> Icons.Rounded.CheckCircle
        WidgetItem.Cpu -> Icons.Rounded.DeveloperBoard
        WidgetItem.Memory -> Icons.Rounded.Memory
        WidgetItem.Storage -> Icons.Rounded.Storage
    }

private val WidgetItem.description: String
    get() = when (this) {
        WidgetItem.Status -> "Server name, status and last update"
        WidgetItem.Cpu -> "With temperature, if it fits"
        WidgetItem.Memory -> "Used of total, if it fits"
        WidgetItem.Storage -> "Used of total, if it fits"
    }

@Composable
private fun WidgetSettings(
    options: WidgetOptions,
    onOptionsChange: (WidgetOptions) -> Unit,
    profiles: List<ServerProfile>,
    server: String?,
    onServerChange: (String?) -> Unit,
    onDone: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).readableWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Widget settings", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineMedium)
            Text(
                "Changes show on your home screen right away.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("Show")
            ListGroup {
                WidgetItem.entries.forEach { item ->
                    val on = options.shows(item)
                    SwitchRow(
                        item.label,
                        checked = on,
                        onCheckedChange = { onOptionsChange(options.toggle(item, it)) },
                        subtitle = item.description,
                        icon = item.icon,
                        // The last one stays on, so the widget always shows something.
                        enabled = !on || options.show.size > 1,
                    )
                }
            }

            SectionHeader("Look")
            ListGroup {
                HearthCard(Modifier.fillMaxWidth(), shape = GroupRowShape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Theme", style = MaterialTheme.typography.titleMedium)
                        Choice(WidgetTheme.entries, options.theme, { it.label }) { onOptionsChange(options.copy(theme = it)) }
                        Text("Text size", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium)
                        Choice(WidgetTextSize.entries, options.textSize, { it.label }) { onOptionsChange(options.copy(textSize = it)) }
                    }
                }
            }
            GroupFootnote("App follows Hearth's theme. Larger text needs a larger widget.")

            // Nothing to pick between with one server (unless the widget was set to one that's gone).
            if (profiles.size > 1 || server != null) {
                SectionHeader("Server")
                ServerPicker(profiles, server, onServerChange)
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp)) {
                Text("Done", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun <T> Choice(entries: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        entries.forEachIndexed { i, entry ->
            SegmentedButton(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                shape = SegmentedButtonDefaults.itemShape(i, entries.size),
            ) { Text(label(entry), maxLines = 1) }
        }
    }
}

@Composable
private fun ServerPicker(profiles: List<ServerProfile>, current: String?, onPick: (String?) -> Unit) {
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
