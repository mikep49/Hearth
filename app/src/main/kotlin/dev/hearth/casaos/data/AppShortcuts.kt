package dev.hearth.casaos.data

/**
 * The apps the App shortcuts widget on Home shows, out of a server's [apps]: the ones [chosen] in Customize
 * Home, in the order they were chosen, or every app with a web page, in the server's order, while none have
 * been chosen. Apps with no web page to open, and chosen ones since uninstalled, are left out.
 */
fun shortcutApps(apps: List<InstalledApp>, chosen: List<String>?, serverBaseUrl: String): List<InstalledApp> {
    val openable = apps.filter { it.webUrl(serverBaseUrl) != null }
    if (chosen == null) return openable
    val byId = openable.associateBy { it.id }
    return chosen.mapNotNull { byId[it] }
}

/**
 * [chosen] with the app [id] added to the widget, after the others, or taken off it. While none have been
 * chosen the widget shows all of [openable], so the first change starts from those.
 */
fun toggledShortcut(chosen: List<String>?, openable: List<InstalledApp>, id: String): List<String> {
    val now = chosen ?: openable.map { it.id }
    return if (id in now) now - id else now + id
}

/** The chosen apps as saved: their ids, with commas between. */
fun savedShortcuts(ids: List<String>): String = ids.joinToString(",")

/** The chosen apps from what was saved, or null if none have been chosen. */
fun shortcuts(saved: String?): List<String>? = saved?.split(',')?.filter { it.isNotBlank() }?.distinct()

/**
 * How [count] shortcuts fit in [slots]: how many apps show, and how many more there are. When they don't all
 * fit, the last slot is left for a button that says how many more there are.
 */
fun shortcutSlots(count: Int, slots: Int): Pair<Int, Int> = when {
    count <= slots -> count to 0
    slots <= 1 -> 0 to count
    else -> (slots - 1) to (count - slots + 1)
}
