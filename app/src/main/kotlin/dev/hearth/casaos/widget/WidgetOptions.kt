package dev.hearth.casaos.widget

import dev.hearth.casaos.ui.theme.ThemeMode
import dev.hearth.casaos.ui.theme.ThemeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Something a stats widget can show. */
enum class WidgetItem(val label: String, val shortLabel: String) {
    /** The server's name, whether it's reachable, and when it last updated. */
    Status("Server status", "Status"),
    Cpu("CPU load", "CPU"),
    Memory("RAM usage", "RAM"),
    Storage("Storage", "Disk"),
}

/** The colors a widget uses. [App] follows the app's theme, the others keep the app's accent. */
enum class WidgetTheme(val label: String) {
    App("App"),
    Light("Light"),
    Dark("Dark"),
    Oled("OLED");

    /** [app]'s theme with this one's light or dark. */
    fun applyTo(app: ThemeSettings): ThemeSettings = when (this) {
        App -> app
        Light -> app.copy(mode = ThemeMode.Light)
        Dark -> app.copy(mode = ThemeMode.Dark, pureBlack = false)
        Oled -> app.copy(mode = ThemeMode.Dark, pureBlack = true)
    }
}

enum class WidgetTextSize(val label: String, val scale: Float) {
    Small("Small", 0.85f),
    Medium("Medium", 1f),
    Large("Large", 1.2f),
}

/** How one placed widget looks. */
@Serializable
data class WidgetOptions(
    /** Never empty: a widget always shows something. */
    val show: Set<WidgetItem> = WidgetItem.entries.toSet(),
    val theme: WidgetTheme = WidgetTheme.App,
    val textSize: WidgetTextSize = WidgetTextSize.Medium,
) {
    fun shows(item: WidgetItem) = item in show

    /** Turns [item] on or off, keeping at least one item on. */
    fun toggle(item: WidgetItem, on: Boolean): WidgetOptions {
        val next = if (on) show + item else show - item
        return if (next.isEmpty()) this else copy(show = next)
    }

    /** A short line for a list of widgets, e.g. "CPU, RAM · Dark". */
    val summary: String
        get() = listOfNotNull(
            WidgetItem.entries.filter { it in show }.joinToString(", ") { it.shortLabel },
            theme.takeIf { it != WidgetTheme.App }?.label,
            textSize.takeIf { it != WidgetTextSize.Medium }?.let { "${it.label} text" },
        ).joinToString(" · ")
}

/** What each placed widget shows and how it looks, by app widget id. A widget with no entry uses the defaults. */
class WidgetOptionsStore(private val storage: WidgetStorage) {
    private val _options = MutableStateFlow(load())
    val options: StateFlow<Map<Int, WidgetOptions>> = _options.asStateFlow()

    operator fun get(widgetId: Int): WidgetOptions = _options.value[widgetId] ?: WidgetOptions()

    fun set(widgetId: Int, options: WidgetOptions) {
        val fixed = if (options.show.isEmpty()) options.copy(show = WidgetOptions().show) else options
        _options.update { if (fixed == WidgetOptions()) it - widgetId else it + (widgetId to fixed) }
        persist()
    }

    /** Drops options for widgets that were removed from the home screen. */
    fun keepOnly(widgetIds: Set<Int>) {
        if (_options.value.keys.all { it in widgetIds }) return
        _options.update { it.filterKeys { id -> id in widgetIds } }
        persist()
    }

    private fun persist() {
        storage.saved = json.encodeToString(savedMap, _options.value.mapKeys { it.key.toString() })
    }

    // Each widget is read on its own, so one that can't be read (e.g. saved by a newer version) doesn't reset the others.
    private fun load(): Map<Int, WidgetOptions> =
        storage.saved?.let { runCatching { json.decodeFromString(rawMap, it) }.getOrNull() }.orEmpty()
            .mapNotNull { (k, v) ->
                val id = k.toIntOrNull() ?: return@mapNotNull null
                val options = runCatching { json.decodeFromJsonElement(WidgetOptions.serializer(), v) }.getOrNull()
                    ?.takeIf { it.show.isNotEmpty() } ?: return@mapNotNull null
                id to options
            }.toMap()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val savedMap = MapSerializer(String.serializer(), WidgetOptions.serializer())
        val rawMap = MapSerializer(String.serializer(), JsonElement.serializer())
    }
}
