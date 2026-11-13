package dev.hearth.casaos.data

import dev.hearth.casaos.data.HomeSize.Large
import dev.hearth.casaos.data.HomeSize.Medium
import dev.hearth.casaos.data.HomeSize.Small

/**
 * How much room a widget takes on Home. A [Small] one is square and sits beside another. The others are as
 * wide as the page, and a [Large] one is taller, with more to show. The names are what's saved.
 */
enum class HomeSize(val label: String) { Small("Small"), Medium("Medium"), Large("Large") }

/**
 * Something Home can show, in one of its [sizes], smallest first. It comes in [defaultSize] until it's resized.
 * The names are what's saved, so renaming one drops it from layouts people have already arranged.
 */
enum class HomeWidget(
    val label: String,
    val about: String,
    val sizes: List<HomeSize>,
    val defaultSize: HomeSize = sizes.first(),
) {
    CpuTile("CPU", "How busy the processor is", listOf(Small, Medium)),
    TemperatureTile("Temperature", "How warm the processor is", listOf(Small, Medium)),
    MemoryTile("Memory", "How much memory is in use", listOf(Small, Medium)),
    StorageTile("Storage", "How full the system disk is", listOf(Small, Medium)),
    ProcessorGraph("Processor graph", "Load over the last two minutes", listOf(Small, Medium, Large), Medium),
    Network("Network", "Download and upload speeds", listOf(Small, Medium, Large), Medium),
    MemoryBar("Memory bar", "Memory in use, as a bar", listOf(Medium)),
    Drives("Drives", "Each drive's health and free space", listOf(Medium));

    /** Whether there's more than one size to pick from. */
    val resizable: Boolean get() = sizes.size > 1

    companion object {
        /** Home as it comes: every widget, the small ones first. */
        val Default: List<HomeWidget> = entries
    }
}

/**
 * What's on Home: its [widgets] top to bottom, and the [sizes] of the ones that have been resized. A widget
 * taken off Home keeps its size for when it's put back.
 */
data class HomeLayout(val widgets: List<HomeWidget>, val sizes: Map<HomeWidget, HomeSize> = emptyMap()) {
    fun sizeOf(widget: HomeWidget): HomeSize = sizes[widget]?.takeIf { it in widget.sizes } ?: widget.defaultSize

    fun isSmall(widget: HomeWidget): Boolean = sizeOf(widget) == Small

    /** The size [widget] takes when its size is changed: the next one up, and round to the smallest after the largest. */
    fun nextSize(widget: HomeWidget): HomeSize = widget.sizes.let { it[(it.indexOf(sizeOf(widget)) + 1) % it.size] }

    /** The layout with [widget] moved to where [target] is, which is what dragging one onto the other does. */
    fun moved(widget: HomeWidget, target: HomeWidget): HomeLayout = copy(widgets = widgets.moved(widget, target))

    /** The layout with [widget] moved [by] places, for moving one without dragging. */
    fun shifted(widget: HomeWidget, by: Int): HomeLayout = copy(widgets = widgets.shifted(widget, by))

    /** The layout with [widget] put on Home: a small one joins the other small ones, any other goes on the end. */
    fun adding(widget: HomeWidget): HomeLayout {
        if (widget in widgets) return this
        val lastSmall = widgets.indexOfLast { isSmall(it) }
        return copy(widgets = if (isSmall(widget) && lastSmall >= 0) widgets.toMutableList().apply { add(lastSmall + 1, widget) } else widgets + widget)
    }

    fun removing(widget: HomeWidget): HomeLayout = copy(widgets = widgets - widget)

    /** The layout with [widget] in [size]. A size the widget doesn't come in changes nothing. */
    fun resized(widget: HomeWidget, size: HomeSize): HomeLayout = when {
        size !in widget.sizes -> this
        // Only sizes that differ from what the widget comes in are kept, so an untouched layout equals [Default].
        size == widget.defaultSize -> copy(sizes = sizes - widget)
        else -> copy(sizes = sizes + (widget to size))
    }

    /** The layout with only the widgets that pass [keep], for leaving out what a server has no reading for. */
    fun only(keep: (HomeWidget) -> Boolean): HomeLayout = copy(widgets = widgets.filter(keep))

    companion object {
        /** Home as it comes: every widget in the size it comes in. */
        val Default = HomeLayout(HomeWidget.Default)
    }
}

/**
 * How Home is laid out on each server. Servers show the [shared] layout until one is given a layout of
 * its [own], kept by the server's id.
 */
data class HomeLayouts(val shared: HomeLayout = HomeLayout.Default, val own: Map<String, HomeLayout> = emptyMap()) {
    /** What [server]'s Home shows. */
    fun of(server: String): HomeLayout = own[server] ?: shared

    /** Whether [server] has a layout of its own rather than the shared one. */
    fun isOwn(server: String): Boolean = server in own

    /** [server]'s Home changed to [layout]: its own layout if it has one, and otherwise the shared one. */
    fun with(server: String, layout: HomeLayout): HomeLayouts {
        val tidy = layout.copy(widgets = layout.widgets.distinct())
        return if (server in own) copy(own = own + (server to tidy)) else copy(shared = tidy)
    }

    /**
     * Gives [server] a layout of its own, which starts as the shared one so nothing moves until it's
     * changed. With [own] false its layout is dropped, and it shows the shared one again.
     */
    fun owning(server: String, own: Boolean): HomeLayouts = when {
        own == isOwn(server) -> this
        own -> copy(own = this.own + (server to shared))
        else -> copy(own = this.own - server)
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

/**
 * The sizes widgets were given, from what was saved: `Widget:Size` pairs with commas between. Names that no
 * longer exist are dropped, and so are sizes a widget doesn't come in or already has.
 */
fun homeSizes(saved: String?): Map<HomeWidget, HomeSize> = saved.orEmpty().split(',').mapNotNull { pair ->
    val widget = HomeWidget.entries.firstOrNull { it.name == pair.substringBefore(':') } ?: return@mapNotNull null
    val size = HomeSize.entries.firstOrNull { it.name == pair.substringAfter(':', "") } ?: return@mapNotNull null
    (widget to size).takeIf { size in widget.sizes && size != widget.defaultSize }
}.toMap()

/** [sizes] as [homeSizes] reads them back. */
fun savedHomeSizes(sizes: Map<HomeWidget, HomeSize>): String = sizes.entries.joinToString(",") { "${it.key.name}:${it.value.name}" }

/** The list with [widget] moved to where [target] is. */
fun List<HomeWidget>.moved(widget: HomeWidget, target: HomeWidget): List<HomeWidget> {
    val from = indexOf(widget)
    val to = indexOf(target)
    if (from < 0 || to < 0 || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** The list with [widget] moved [by] places. */
fun List<HomeWidget>.shifted(widget: HomeWidget, by: Int): List<HomeWidget> {
    val from = indexOf(widget)
    val to = from + by
    if (from < 0 || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** A run of widgets on Home: [tiles] are the small ones, laid out side by side, and the rest go one under another. */
data class HomeSection(val tiles: Boolean, val widgets: List<HomeWidget>)

/** The layout's widgets in order, with small neighbours grouped, since those are laid out together. */
fun homeSections(layout: HomeLayout): List<HomeSection> {
    val sections = mutableListOf<HomeSection>()
    for (widget in layout.widgets) {
        val small = layout.isSmall(widget)
        val last = sections.lastOrNull()
        if (last != null && last.tiles == small) sections[sections.lastIndex] = last.copy(widgets = last.widgets + widget)
        else sections += HomeSection(small, listOf(widget))
    }
    return sections
}

/**
 * How a run of small [widgets] sits on Home with [columns] to a row: each row, and whether its widgets are
 * shown small. One with a row to itself is shown at medium size rather than leave a hole beside it.
 */
fun tileRows(widgets: List<HomeWidget>, columns: Int): List<Pair<List<HomeWidget>, HomeSize>> =
    widgets.chunked(columns).map { row -> row to if (row.size == 1 && Medium in row[0].sizes) Medium else Small }
