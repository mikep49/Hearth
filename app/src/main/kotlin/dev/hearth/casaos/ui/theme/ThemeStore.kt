package dev.hearth.casaos.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists the appearance choice. Kept separate from the session so it survives sign-out. */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    fun update(settings: ThemeSettings) {
        prefs.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putString(KEY_PALETTE, settings.palette.name)
            .putBoolean(KEY_BLACK, settings.pureBlack)
            .putString(KEY_STYLE, settings.style.name)
            .apply()
        _settings.value = settings
    }

    private fun load(): ThemeSettings {
        val mode = enumOrNull<ThemeMode>(prefs.getString(KEY_MODE, null)) ?: ThemeMode.System
        // A palette saved on a newer Android (e.g. Wallpaper) may be unavailable after a restore elsewhere.
        val palette = enumOrNull<Palette>(prefs.getString(KEY_PALETTE, null))?.takeIf { it in Palette.available } ?: Palette.Ember
        val style = enumOrNull<UiStyle>(prefs.getString(KEY_STYLE, null)) ?: UiStyle.Android
        return ThemeSettings(mode, palette, prefs.getBoolean(KEY_BLACK, false), style)
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_PALETTE = "palette"
        const val KEY_BLACK = "pure_black"
        const val KEY_STYLE = "style"
    }
}
