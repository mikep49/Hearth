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

    private val _nickname = MutableStateFlow(prefs.getString(KEY_NICKNAME, "").orEmpty())

    /** The name used around Hearth. Blank uses the current server's CasaOS username. */
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    fun setNickname(value: String) {
        prefs.edit().putString(KEY_NICKNAME, value).apply()
        _nickname.value = value
    }

    private val _openAppsIn = MutableStateFlow(
        OpenAppsIn.entries.firstOrNull { it.name == prefs.getString(KEY_OPEN_IN, null) } ?: OpenAppsIn.Hearth,
    )
    val openAppsIn: StateFlow<OpenAppsIn> = _openAppsIn.asStateFlow()

    fun setOpenAppsIn(value: OpenAppsIn) {
        prefs.edit().putString(KEY_OPEN_IN, value.name).apply()
        _openAppsIn.value = value
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

    private val _fileThumbnails = MutableStateFlow(prefs.getBoolean(KEY_FILE_THUMBNAILS, false))

    /** Whether Files shows a folder as a grid of thumbnails rather than a list. One choice for every folder and server. */
    val fileThumbnails: StateFlow<Boolean> = _fileThumbnails.asStateFlow()

    fun setFileThumbnails(value: Boolean) {
        prefs.edit().putBoolean(KEY_FILE_THUMBNAILS, value).apply()
        _fileThumbnails.value = value
    }

    private val _mobileThumbnails = MutableStateFlow(
        ThumbnailDownload.entries.firstOrNull { it.name == prefs.getString(KEY_MOBILE_THUMBNAILS, null) } ?: ThumbnailDownload.Preview,
    )

    /**
     * How much of a photo thumbnails may download on mobile data, in Files, the gallery and Android Files. On
     * Wi-Fi they may always download whole photos. One choice for every server.
     */
    val mobileThumbnails: StateFlow<ThumbnailDownload> = _mobileThumbnails.asStateFlow()

    fun setMobileThumbnails(value: ThumbnailDownload) {
        prefs.edit().putString(KEY_MOBILE_THUMBNAILS, value.name).apply()
        _mobileThumbnails.value = value
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

    private fun savedHomeLayout(of: String) = HomeLayout(
        homeWidgets(prefs.getString(KEY_HOME_WIDGETS + of, null), prefs.getString(KEY_HOME_HIDDEN + of, null)),
        homeSizes(prefs.getString(KEY_HOME_SIZES + of, null)),
    )

    private val _homeLayouts = MutableStateFlow(
        HomeLayouts(
            shared = savedHomeLayout(of = ""),
            // A server's own layout is saved like the shared one, with "/" and the server's id after each key.
            own = prefs.all.keys.filter { it.startsWith("$KEY_HOME_WIDGETS/") }
                .associate { key -> key.substringAfter('/').let { id -> id to savedHomeLayout(of = "/$id") } },
        ),
    )

    /**
     * What Home shows, top to bottom and at what size, as arranged in Customize Home: one arrangement that
     * servers share, and another for each server that was given its own.
     */
    val homeLayouts: StateFlow<HomeLayouts> = _homeLayouts.asStateFlow()

    /** Changes what [server]'s Home shows. That's the shared arrangement unless the server has its own. */
    fun setHomeLayout(server: String, value: HomeLayout) = saveHomeLayouts(_homeLayouts.value.with(server, value))

    /** Gives [server] an arrangement of its own, or with [own] false puts it back on the shared one. */
    fun setOwnHomeLayout(server: String, own: Boolean) = saveHomeLayouts(_homeLayouts.value.owning(server, own))

    private fun saveHomeLayouts(next: HomeLayouts) {
        val before = _homeLayouts.value
        if (next == before) return
        val edit = prefs.edit()
        fun put(of: String, layout: HomeLayout) {
            // The removed ones are saved too, which tells them apart from widgets a later version adds.
            edit.putString(KEY_HOME_WIDGETS + of, layout.widgets.joinToString(",") { it.name })
                .putString(KEY_HOME_HIDDEN + of, HomeWidget.entries.filter { it !in layout.widgets }.joinToString(",") { it.name })
                .putString(KEY_HOME_SIZES + of, savedHomeSizes(layout.sizes))
        }
        if (next.shared != before.shared) put("", next.shared)
        for ((id, layout) in next.own) if (before.own[id] != layout) put("/$id", layout)
        for (id in before.own.keys - next.own.keys) {
            edit.remove("$KEY_HOME_WIDGETS/$id").remove("$KEY_HOME_HIDDEN/$id").remove("$KEY_HOME_SIZES/$id")
        }
        edit.apply()
        _homeLayouts.value = next
    }

    private val _appShortcuts = MutableStateFlow(
        prefs.all.keys.filter { it.startsWith("$KEY_APP_SHORTCUTS/") }
            .associate { key -> key.substringAfter('/') to shortcuts(prefs.getString(key, null)).orEmpty() },
    )

    /**
     * The apps chosen for the App shortcuts widget on Home, by server id, in the order they were chosen. Apps
     * differ from server to server, so each keeps its own choice. A server not here hasn't chosen any yet.
     */
    val appShortcuts: StateFlow<Map<String, List<String>>> = _appShortcuts.asStateFlow()

    /** Chooses [server]'s app shortcuts. Null goes back to showing all its apps. */
    fun setAppShortcuts(server: String, ids: List<String>?) {
        if (_appShortcuts.value[server] == ids) return
        val key = "$KEY_APP_SHORTCUTS/$server"
        if (ids == null) {
            prefs.edit().remove(key).apply()
            _appShortcuts.value -= server
        } else {
            prefs.edit().putString(key, savedShortcuts(ids)).apply()
            _appShortcuts.value += server to ids
        }
    }

    /** The terminal's text size in sp, as last set by pinching or from its menu. Read once when a terminal opens. */
    var terminalFontSp: Float
        get() = prefs.getFloat(KEY_TERMINAL_FONT, DEFAULT_TERMINAL_FONT_SP)
        set(value) = prefs.edit().putFloat(KEY_TERMINAL_FONT, value).apply()

    companion object {
        private const val KEY_NICKNAME = "nickname"
        const val DEFAULT_TERMINAL_FONT_SP = 12f
        private const val KEY_TERMINAL_FONT = "terminal_font_sp"
        private const val KEY_HOME_WIDGETS = "home_widgets"
        private const val KEY_HOME_HIDDEN = "home_widgets_hidden"
        private const val KEY_HOME_SIZES = "home_widget_sizes"
        private const val KEY_APP_SHORTCUTS = "app_shortcuts"
        private const val KEY_STRICT_BROWSER = "strict_browser"
        private const val KEY_SHOW_IN_FILES = "show_in_files"
        private const val KEY_ALERTS = "alerts"
        private const val KEY_ALERT_OFFLINE = "alert_offline"
        private const val KEY_ALERT_STORAGE = "alert_storage"
        private const val KEY_ALERT_APP_STOPPED = "alert_app_stopped"
        private const val KEY_ALERT_UPDATES = "alert_updates"
        private const val KEY_OPEN_IN = "open_apps_in"
        private const val KEY_SORT_BY = "file_sort_by"
        private const val KEY_SORT_DESC = "file_sort_descending"
        private const val KEY_SHOW_HIDDEN = "file_show_hidden"
        private const val KEY_FILE_THUMBNAILS = "file_thumbnails"
        private const val KEY_MOBILE_THUMBNAILS = "mobile_thumbnails"
    }
}
