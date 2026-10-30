package dev.hearth.casaos.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where an app's web UI opens. */
enum class OpenAppsIn(val label: String) { Hearth("Hearth"), Browser("Browser") }

/** General preferences. Like the theme, they're kept apart from the session so they survive sign-out. */
class PrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val _openAppsIn = MutableStateFlow(
        OpenAppsIn.entries.firstOrNull { it.name == prefs.getString(KEY_OPEN_IN, null) } ?: OpenAppsIn.Hearth,
    )
    val openAppsIn: StateFlow<OpenAppsIn> = _openAppsIn.asStateFlow()

    fun setOpenAppsIn(value: OpenAppsIn) {
        prefs.edit().putString(KEY_OPEN_IN, value.name).apply()
        _openAppsIn.value = value
    }

    private val _appsCardCollapsed = MutableStateFlow(prefs.getBoolean(KEY_APPS_COLLAPSED, false))

    /** Whether the per-app list on Home is folded down to its title. */
    val appsCardCollapsed: StateFlow<Boolean> = _appsCardCollapsed.asStateFlow()

    fun setAppsCardCollapsed(value: Boolean) {
        prefs.edit().putBoolean(KEY_APPS_COLLAPSED, value).apply()
        _appsCardCollapsed.value = value
    }

    private companion object {
        const val KEY_OPEN_IN = "open_apps_in"
        const val KEY_APPS_COLLAPSED = "apps_card_collapsed"
    }
}
