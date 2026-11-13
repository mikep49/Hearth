package dev.hearth.casaos.data

/**
 * Something Home can show. A [tile] is square and sits beside another; the rest are cards as wide as the page.
 * The names are what's saved, so renaming one drops it from layouts people have already arranged.
 */
enum class HomeWidget(val label: String, val about: String, val tile: Boolean) {
    CpuTile("CPU", "How busy the processor is", true),
    TemperatureTile("Temperature", "How warm the processor is", true),
    MemoryTile("Memory", "How much memory is in use", true),
    StorageTile("Storage", "How full the system disk is", true),
    ProcessorGraph("Processor graph", "Load over the last two minutes", false),
    Network("Network", "Download and upload speeds", false),
    MemoryBar("Memory bar", "Memory in use, as a bar", false),
    Drives("Drives", "Each drive's health and free space", false);

    companion object {
        /** Home as it comes: every widget, tiles first. */
        val Default: List<HomeWidget> = entries
    }
}

/**
 * The widgets on Home, in order, from what was saved: [shown] and [hidden] are names with commas between.
 * A widget in neither list was added to Hearth after the layout was saved, and goes on the end.
 */
fun homeWidgets(shown: String?, hidden: String?): List<HomeWidget> {
    if (shown == null) return HomeWidget.Default
    fun named(saved: String?) = saved.orEmpty().split(',').mapNotNull { name -> HomeWidget.entries.firstOrNull { it.name == name } }
    val on = named(shown).distinct()
    val off = named(hidden).toSet()
    return on.filter { it !in off } + HomeWidget.entries.filter { it !in on && it !in off }
}

/** The list with [widget] moved to where [target] is, which is what dragging one onto the other does. */
fun List<HomeWidget>.moved(widget: HomeWidget, target: HomeWidget): List<HomeWidget> {
    val from = indexOf(widget)
    val to = indexOf(target)
    if (from < 0 || to < 0 || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** The list with [widget] moved [by] places, for moving one without dragging. */
fun List<HomeWidget>.shifted(widget: HomeWidget, by: Int): List<HomeWidget> {
    val from = indexOf(widget)
    val to = from + by
    if (from < 0 || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** The list with [widget] put on Home: a tile joins the other tiles, a card goes on the end. */
fun List<HomeWidget>.adding(widget: HomeWidget): List<HomeWidget> {
    if (widget in this) return this
    val lastTile = indexOfLast { it.tile }
    return if (widget.tile && lastTile >= 0) toMutableList().apply { add(lastTile + 1, widget) } else this + widget
}

/** A run of widgets of one kind on Home: [tiles] laid out side by side, or cards one under another. */
data class HomeSection(val tiles: Boolean, val widgets: List<HomeWidget>)

/** [widgets] in order, with neighbours of the same kind grouped, since tiles are laid out together. */
fun homeSections(widgets: List<HomeWidget>): List<HomeSection> {
    val sections = mutableListOf<HomeSection>()
    for (widget in widgets) {
        val last = sections.lastOrNull()
        if (last != null && last.tiles == widget.tile) sections[sections.lastIndex] = last.copy(widgets = last.widgets + widget)
        else sections += HomeSection(widget.tile, listOf(widget))
    }
    return sections
}

/**
 * The tiles that get a row to themselves when tiles go two to a row: the odd one at the end of a run. Home
 * stretches such a tile across the page rather than leave a hole beside it.
 */
fun loneTiles(widgets: List<HomeWidget>): Set<HomeWidget> =
    homeSections(widgets).filter { it.tiles && it.widgets.size % 2 == 1 }.map { it.widgets.last() }.toSet()
