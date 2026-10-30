package dev.hearth.casaos.widget

import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.hearth.casaos.AppContainer
import dev.hearth.casaos.BuildConfig
import dev.hearth.casaos.HearthApp
import dev.hearth.casaos.MainActivity
import dev.hearth.casaos.R
import dev.hearth.casaos.ui.components.formatBytes
import dev.hearth.casaos.ui.theme.ThemeMode
import dev.hearth.casaos.ui.theme.ThemeSettings
import dev.hearth.casaos.ui.theme.hearthColorScheme
import dev.hearth.casaos.ui.theme.hearthColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import kotlin.math.roundToInt
import androidx.glance.color.ColorProvider as dayNight
import androidx.glance.material3.ColorProviders as material3Colors

/** The layouts the widget switches between as it's resized. */
internal enum class StatsLayout(val minSize: DpSize) {
    /** 2×1: three numbers in a row. */
    Strip(DpSize(110.dp, 40.dp)),
    /** A bar per stat under a header with the server and a refresh button. */
    Bars(DpSize(150.dp, 110.dp)),
    /** Bars, with room for the CPU temperature. */
    WideBars(DpSize(230.dp, 110.dp)),
    /** Squarish (2×2): each stat's name and number above a full-width bar. */
    Stacked(DpSize(150.dp, 170.dp)),
    /** Bars with a detail line under each: cores and temperature, used of total. */
    Detailed(DpSize(230.dp, 200.dp));

    companion object {
        /** The largest layout that fits [size]; [Strip] when none does. */
        fun forSize(size: DpSize): StatsLayout =
            entries.lastOrNull { size.width >= it.minSize.width && size.height >= it.minSize.height } ?: Strip
    }
}

/** A home screen widget with the server's CPU, memory and storage use. */
class ServerStatsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(StatsLayout.entries.map { it.minSize }.toSet())
    override val previewSizeMode = SizeMode.Responsive(StatsLayout.entries.map { it.minSize }.toSet())

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.container
        provideContent {
            val session by container.session.session.collectAsState()
            val stats by container.widgetStats.state.collectAsState()
            val theme by container.theme.settings.collectAsState()
            val baseUrl = session?.baseUrl
            // Covers placing the widget, the 30-minute update and signing in; skipped if a reading is recent.
            LaunchedEffect(baseUrl) { if (baseUrl != null) container.widgetStats.refresh(ServerStatsWidgets.FRESH_MS) }
            StatsWidget(theme, baseUrl, stats.takeIf { it.snapshot == null || it.snapshot.baseUrl == baseUrl } ?: WidgetStats())
        }
    }

    /** What the widget picker shows (Android 15+), drawn with made-up numbers. */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val theme = context.container.theme.settings.value
        provideContent { StatsWidget(theme, SAMPLE.baseUrl, WidgetStats(SAMPLE), interactive = false) }
    }

    private companion object {
        val SAMPLE = StatsSnapshot(
            baseUrl = "http://casaos.local",
            cpuPercent = 23.0, cpuCores = 4, cpuTempC = 48,
            memUsed = 6_100_000_000, memTotal = 16_000_000_000, memPercent = 38.0,
            diskUsed = 1_400_000_000_000, diskTotal = 4_000_000_000_000,
            sampledAtMs = System.currentTimeMillis(),
        )
    }
}

class ServerStatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ServerStatsWidget()
}

/** The refresh button. */
class RefreshStatsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // A few seconds' grace, so a double tap makes one request.
        ServerStatsWidgets.refresh(context, maxAgeMs = 3_000)
    }
}

/** Keeps placed widgets up to date from the rest of the app. */
object ServerStatsWidgets {
    /** A reading younger than this isn't fetched again when a widget or the app asks. */
    const val FRESH_MS = 60_000L

    private suspend fun anyPlaced(context: Context) =
        GlanceAppWidgetManager(context).getGlanceIds(ServerStatsWidget::class.java).isNotEmpty()

    /** Redraws every placed widget, e.g. after sign-out or a theme change. */
    suspend fun redraw(context: Context) {
        if (anyPlaced(context)) ServerStatsWidget().updateAll(context)
    }

    /** Fetches a new reading, unless one is younger than [maxAgeMs], and redraws. Does nothing when no widget is placed. */
    suspend fun refresh(context: Context, maxAgeMs: Long = FRESH_MS) {
        if (!anyPlaced(context)) return
        coroutineScope {
            launch { context.container.widgetStats.refresh(maxAgeMs) }
            // Drawn while it loads too, so the refresh button turns into a spinner.
            ServerStatsWidget().updateAll(context)
        }
        ServerStatsWidget().updateAll(context)
    }

    /**
     * Gives the widget picker a preview in the app's theme (Android 15+). Android limits how often this
     * can happen, so it's only redone when the app or the theme changes.
     */
    suspend fun publishPreview(context: Context, theme: ThemeSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val prefs = context.getSharedPreferences("widget_preview", Context.MODE_PRIVATE)
        val key = "${BuildConfig.VERSION_CODE}|$theme"
        if (prefs.getString(KEY_PUBLISHED, null) == key) return
        val result = GlanceAppWidgetManager(context).setWidgetPreviews(ServerStatsWidgetReceiver::class)
        if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) prefs.edit().putString(KEY_PUBLISHED, key).apply()
    }

    private const val KEY_PUBLISHED = "published_for"
}

private val Context.container: AppContainer get() = (applicationContext as HearthApp).container

// region UI

/** The app's theme as widget colors. "System" follows the phone's dark mode, like the app does. */
private class WidgetColors(val scheme: ColorProviders, val cpu: ColorProvider, val memory: ColorProvider, val storage: ColorProvider) {
    companion object {
        fun of(context: Context, theme: ThemeSettings): WidgetColors {
            val light = hearthColorScheme(context, theme, dark = false)
            val dark = hearthColorScheme(context, theme, dark = true)
            val lightChart = hearthColors(light, dark = false)
            val darkChart = hearthColors(dark, dark = true)
            fun pick(l: Color, d: Color): ColorProvider = when (theme.mode) {
                ThemeMode.System -> dayNight(day = l, night = d)
                ThemeMode.Light -> ColorProvider(l)
                ThemeMode.Dark -> ColorProvider(d)
            }
            return WidgetColors(
                scheme = when (theme.mode) {
                    ThemeMode.System -> material3Colors(light = light, dark = dark)
                    ThemeMode.Light -> material3Colors(light)
                    ThemeMode.Dark -> material3Colors(dark)
                },
                cpu = pick(lightChart.cpu, darkChart.cpu),
                memory = pick(lightChart.memory, darkChart.memory),
                storage = pick(lightChart.storage, darkChart.storage),
            )
        }
    }
}

private class Stat(val label: String, val shortLabel: String, val percent: Double, val color: ColorProvider, val detail: String)

private fun StatsSnapshot.stats(colors: WidgetColors): List<Stat> = buildList {
    val cpuDetail = listOfNotNull(
        cpuCores.takeIf { it > 0 }?.let { if (it == 1) "1 core" else "$it cores" },
        cpuTempC?.takeIf { it > 0 }?.let { "$it°C" },
    ).joinToString(" · ")
    add(Stat("CPU", "CPU", cpuPercent, colors.cpu, cpuDetail))
    add(Stat("Memory", "RAM", memPercent, colors.memory, "${formatBytes(memUsed)} of ${formatBytes(memTotal)}"))
    val disk = diskPercent
    if (disk != null && diskUsed != null && diskTotal != null) {
        add(Stat("Storage", "Disk", disk, colors.storage, "${formatBytes(diskUsed)} of ${formatBytes(diskTotal)}"))
    }
}

private fun percent(value: Double) = "${value.coerceIn(0.0, 100.0).roundToInt()}%"

@Composable
private fun StatsWidget(theme: ThemeSettings, baseUrl: String?, stats: WidgetStats, interactive: Boolean = true) {
    val context = LocalContext.current
    val colors = remember(theme) { WidgetColors.of(context, theme) }
    val layout = StatsLayout.forSize(LocalSize.current)
    GlanceTheme(colors.scheme) {
        var root = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) root = root.cornerRadius(android.R.dimen.system_app_widget_background_radius)
        if (interactive) root = root.clickable(actionStartActivity<MainActivity>())
        val padding = if (layout == StatsLayout.Strip) 8.dp else 14.dp
        Box(root.padding(horizontal = padding, vertical = if (layout == StatsLayout.Strip) 6.dp else 12.dp)) {
            val snapshot = stats.snapshot
            when {
                baseUrl == null -> Message("Sign in to Hearth", "to see your server here", layout)
                snapshot == null && stats.error != null -> Column(GlanceModifier.fillMaxSize()) {
                    if (layout != StatsLayout.Strip) Header(baseUrl, stats, interactive)
                    Message("Can't reach your server", stats.error, layout)
                }
                snapshot == null -> Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(GlanceModifier.size(20.dp), color = GlanceTheme.colors.primary)
                }
                layout == StatsLayout.Strip -> Strip(snapshot.stats(colors), stale = stats.error != null)
                else -> Column(GlanceModifier.fillMaxSize()) {
                    Header(baseUrl, stats, interactive)
                    Spacer(GlanceModifier.defaultWeight())
                    val rows = snapshot.stats(colors)
                    rows.forEachIndexed { i, stat ->
                        if (i > 0) Spacer(GlanceModifier.height(if (layout == StatsLayout.Bars || layout == StatsLayout.WideBars) 6.dp else 8.dp))
                        when (layout) {
                            StatsLayout.Detailed -> StackedRow(stat, showDetail = true)
                            StatsLayout.Stacked -> StackedRow(stat, showDetail = false)
                            else -> BarRow(stat, showTemp = layout == StatsLayout.WideBars, snapshot)
                        }
                    }
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun Header(baseUrl: String, stats: WidgetStats, interactive: Boolean) {
    val context = LocalContext.current
    val sampledAt = stats.snapshot?.sampledAtMs
    val time = sampledAt?.let { DateUtils.formatSameDayTime(it, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT).toString() }
    val status = when {
        stats.error != null && time != null -> "Offline · $time"
        stats.error != null -> "Offline"
        else -> time.orEmpty()
    }
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The status gets its own line, so a narrow widget doesn't cut the server's name short.
        Column(GlanceModifier.defaultWeight()) {
            Text(
                baseUrl.substringAfter("://").trimEnd('/'),
                style = TextStyle(GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = TextStyle(if (stats.error != null) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
        }
        var button = GlanceModifier.size(28.dp)
        if (interactive) button = button.clickable(actionRunCallback<RefreshStatsAction>())
        Box(button, contentAlignment = Alignment.Center) {
            if (stats.refreshing) {
                CircularProgressIndicator(GlanceModifier.size(16.dp), color = GlanceTheme.colors.primary)
            } else {
                Image(
                    ImageProvider(R.drawable.ic_widget_refresh),
                    contentDescription = context.getString(R.string.widget_refresh),
                    GlanceModifier.size(18.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                )
            }
        }
    }
}

@Composable
private fun BarRow(stat: Stat, showTemp: Boolean, snapshot: StatsSnapshot) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stat.shortLabel,
            GlanceModifier.width(36.dp),
            style = TextStyle(GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Bar(stat, GlanceModifier.defaultWeight())
        Text(
            percent(stat.percent),
            GlanceModifier.width(40.dp),
            style = TextStyle(GlanceTheme.colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End),
            maxLines = 1,
        )
        if (showTemp) {
            val temp = snapshot.cpuTempC?.takeIf { stat.shortLabel == "CPU" && it > 0 }
            Text(
                temp?.let { "$it°C" }.orEmpty(),
                GlanceModifier.width(44.dp),
                style = TextStyle(GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, textAlign = TextAlign.End),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StackedRow(stat: Stat, showDetail: Boolean) {
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stat.label,
                GlanceModifier.defaultWeight(),
                style = TextStyle(GlanceTheme.colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Text(
                percent(stat.percent),
                style = TextStyle(stat.color, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(4.dp))
        Bar(stat, GlanceModifier.fillMaxWidth())
        if (showDetail && stat.detail.isNotEmpty()) {
            Text(
                stat.detail,
                GlanceModifier.padding(top = 2.dp),
                style = TextStyle(GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Bar(stat: Stat, modifier: GlanceModifier) {
    LinearProgressIndicator(
        progress = (stat.percent / 100).coerceIn(0.0, 1.0).toFloat(),
        modifier = modifier.height(6.dp),
        color = stat.color,
        backgroundColor = GlanceTheme.colors.surfaceVariant,
    )
}

/** Three numbers side by side, for the smallest size. A dimmed label marks a reading that couldn't be updated. */
@Composable
private fun Strip(stats: List<Stat>, stale: Boolean) {
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        stats.forEach { stat ->
            Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    percent(stat.percent),
                    style = TextStyle(stat.color, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    stat.shortLabel,
                    style = TextStyle(if (stale) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Message(title: String, detail: String, layout: StatsLayout) {
    Column(
        GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = TextStyle(GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            maxLines = 1,
        )
        if (layout != StatsLayout.Strip) {
            Text(
                detail,
                GlanceModifier.padding(top = 2.dp),
                style = TextStyle(GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, textAlign = TextAlign.Center),
                maxLines = 2,
            )
        }
    }
}

// endregion
