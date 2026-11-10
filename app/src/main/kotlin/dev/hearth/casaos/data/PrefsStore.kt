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

    private val _fileSort = MutableStateFlow(
        FileSort(
            by = SortBy.entries.firstOrNull { it.name == prefs.getString(KEY_SORT_BY, null) } ?: SortBy.Name,
            descending = prefs.getBoolean(KEY_SORT_DESC, false),
            showHidden = prefs.getBoolean(KEY_SHOW_HIDDEN, false),
        ),
    )

    /** How Files orders folders and whether it shows hidden files. One choice for every folder and server. */
    val fileSort: StateFlow<FileSort> = _fileSort.asStateFlow()

    fun setFileSort(value: FileSort) {
        prefs.edit()
            .putString(KEY_SORT_BY, value.by.name)
            .putBoolean(KEY_SORT_DESC, value.descending)
            .putBoolean(KEY_SHOW_HIDDEN, value.showHidden)
            .apply()
        _fileSort.value = value
    }

    private val _alerts = MutableStateFlow(
        AlertSettings(
            enabled = prefs.getBoolean(KEY_ALERTS, false),
            offline = prefs.getBoolean(KEY_ALERT_OFFLINE, true),
            storage = prefs.getBoolean(KEY_ALERT_STORAGE, true),
            appStopped = prefs.getBoolean(KEY_ALERT_APP_STOPPED, true),
            updates = prefs.getBoolean(KEY_ALERT_UPDATES, true),
        ),
    )

    /** Which notifications the background check sends. One choice for every server. */
    val alerts: StateFlow<AlertSettings> = _alerts.asStateFlow()

    fun setAlerts(value: AlertSettings) {
        prefs.edit()
            .putBoolean(KEY_ALERTS, value.enabled)
            .putBoolean(KEY_ALERT_OFFLINE, value.offline)
            .putBoolean(KEY_ALERT_STORAGE, value.storage)
            .putBoolean(KEY_ALERT_APP_STOPPED, value.appStopped)
            .putBoolean(KEY_ALERT_UPDATES, value.updates)
            .apply()
        _alerts.value = value
    }

    private val _showInFiles = MutableStateFlow(prefs.getBoolean(KEY_SHOW_IN_FILES, false))

    /**
     * Whether signed-in servers show in Android's Files app and other apps' file pickers. Turned off when the
     * app lock is turned on, since the Files app can't ask for it.
     */
    val showInFiles: StateFlow<Boolean> = _showInFiles.asStateFlow()

    fun setShowInFiles(value: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_IN_FILES, value).apply()
        _showInFiles.value = value
    }

    private val _strictBrowser = MutableStateFlow(prefs.getBoolean(KEY_STRICT_BROWSER, false))

    /**
     * Whether apps opened in Hearth are kept from cookies set by other sites they embed, and from loading
     * plain-HTTP parts into an HTTPS page. Off by default, since some apps rely on one or the other.
     */
    val strictBrowser: StateFlow<Boolean> = _strictBrowser.asStateFlow()

    fun setStrictBrowser(value: Boolean) {
        prefs.edit().putBoolean(KEY_STRICT_BROWSER, value).apply()
        _strictBrowser.value = value
    }

    private companion object {
        const val KEY_STRICT_BROWSER = "strict_browser"
        const val KEY_SHOW_IN_FILES = "show_in_files"
        const val KEY_ALERTS = "alerts"
        const val KEY_ALERT_OFFLINE = "alert_offline"
        const val KEY_ALERT_STORAGE = "alert_storage"
        const val KEY_ALERT_APP_STOPPED = "alert_app_stopped"
        const val KEY_ALERT_UPDATES = "alert_updates"
        const val KEY_OPEN_IN = "open_apps_in"
        const val KEY_APPS_COLLAPSED = "apps_card_collapsed"
        const val KEY_SORT_BY = "file_sort_by"
        const val KEY_SORT_DESC = "file_sort_descending"
        const val KEY_SHOW_HIDDEN = "file_show_hidden"
    }
}
