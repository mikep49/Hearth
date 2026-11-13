package dev.hearth.casaos.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hearth.casaos.data.HomeWidget
import dev.hearth.casaos.data.adding
import dev.hearth.casaos.data.loneTiles
import dev.hearth.casaos.data.moved
import dev.hearth.casaos.data.shifted
import dev.hearth.casaos.ui.components.BackButton
import dev.hearth.casaos.ui.components.CardRadius
import dev.hearth.casaos.ui.components.Chevron
import dev.hearth.casaos.ui.components.GroupFootnote
import dev.hearth.casaos.ui.components.IconTile
import dev.hearth.casaos.ui.components.ListGroup
import dev.hearth.casaos.ui.components.ListRow
import dev.hearth.casaos.ui.components.ReadableGutter
import dev.hearth.casaos.ui.components.SectionHeader
import dev.hearth.casaos.ui.components.hearthTopBarColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

private val HomeWidget.icon: ImageVector
    get() = when (this) {
        HomeWidget.CpuTile -> Icons.Rounded.DeveloperBoard
        HomeWidget.TemperatureTile -> Icons.Rounded.DeviceThermostat
        HomeWidget.MemoryTile -> Icons.Rounded.Memory
        HomeWidget.StorageTile -> Icons.Rounded.Storage
        HomeWidget.ProcessorGraph -> Icons.Rounded.ShowChart
        HomeWidget.Network -> Icons.Rounded.SwapVert
        HomeWidget.MemoryBar -> Icons.Rounded.LinearScale
        HomeWidget.Drives -> Icons.Rounded.Dns
    }

/** What the row that opens the page says: how much of what there is has been put on Home. */
internal fun homeWidgetsSummary(shown: List<HomeWidget>): String = when (shown.size) {
    0 -> "Nothing on Home"
    HomeWidget.entries.size -> "All ${shown.size} widgets"
    else -> "${shown.size} of ${HomeWidget.entries.size} widgets"
}

/** The row on the Appearance page that opens [CustomizeHomeScreen]. */
@Composable
fun CustomizeHomeRow(shown: List<HomeWidget>, onOpen: () -> Unit) {
    SectionHeader("Home")
    ListGroup {
        ListRow(
            "Customize Home",
            subtitle = homeWidgetsSummary(shown),
            icon = Icons.Rounded.DashboardCustomize,
            trailing = { Chevron() },
            onClick = onOpen,
        )
    }
}

private val Side = 20.dp
private val Gap = 12.dp

/**
 * Settings › Appearance › Customize Home: Home's widgets laid out as they are on Home, tiles two to a
 * row and cards across it. Holding one picks it up to drag it somewhere else, its button takes it off
 * Home, and the ones taken off wait underneath to be put back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeHomeScreen(viewModel: SettingsViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val shown by viewModel.homeWidgets.collectAsStateWithLifecycle()
    val hidden = HomeWidget.entries.filter { it !in shown }
    val lone = loneTiles(shown)
    val haptic = LocalHapticFeedback.current

    BackHandler(onBack = onClose)

    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val drag = remember(grid, scope) {
        WidgetDrag(grid, scope) { widget, target ->
            // The grid follows its first visible row when that row moves, which would scroll the page mid-drag.
            grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
            viewModel.setHomeWidgets(viewModel.homeWidgets.value.moved(widget, target))
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onClose) },
                title = { Text("Customize Home") },
                actions = {
                    TextButton(onClick = { viewModel.setHomeWidgets(HomeWidget.Default) }, enabled = shown != HomeWidget.Default) { Text("Reset") }
                },
                colors = hearthTopBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        ReadableGutter(Side) { side ->
            // Items are placed from inside the padding, and touches are measured from the grid's corner.
            drag.origin = with(LocalDensity.current) { Offset(side.toPx(), 12.dp.toPx()) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = grid,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .pointerInput(drag) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { at -> if (drag.start(at)) haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDrag = { change, by -> change.consume(); drag.drag(by) },
                            onDragEnd = drag::end,
                            onDragCancel = drag::end,
                        )
                    },
                contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(Gap),
                verticalArrangement = Arrangement.spacedBy(Gap),
                // A widget being carried shouldn't take the page with it.
                userScrollEnabled = drag.carried == null,
            ) {
                item(key = "intro", span = { GridItemSpan(maxLineSpan) }) {
                    GroupFootnote(
                        if (shown.isEmpty()) "Nothing is on Home. Add widgets from the list below."
                        else "Touch and hold a widget, then drag it to where you want it on Home.",
                        Modifier.padding(bottom = 4.dp).animateItem(),
                    )
                }
                items(shown, key = { it }, span = { GridItemSpan(if (it.tile && it !in lone) 1 else maxLineSpan) }) { widget ->
                    Carried(drag, widget) { lifted ->
                        PlacedWidget(
                            widget,
                            lifted,
                            onRemove = { viewModel.setHomeWidgets(shown - widget) },
                            onShift = { by -> viewModel.setHomeWidgets(shown.shifted(widget, by)) },
                            first = widget == shown.first(),
                            last = widget == shown.last(),
                        )
                    }
                }
                if (hidden.isNotEmpty()) {
                    item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader("Add widgets", Modifier.animateItem())
                    }
                    items(hidden, key = { "add:${it.name}" }, span = { GridItemSpan(maxLineSpan) }) { widget ->
                        ListRow(
                            widget.label,
                            Modifier.animateItem(),
                            subtitle = widget.about,
                            icon = widget.icon,
                            shape = RoundedCornerShape(CardRadius),
                            trailing = { Icon(Icons.Rounded.AddCircleOutline, null, Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.primary) },
                            onClick = { viewModel.setHomeWidgets(shown.adding(widget)) },
                        )
                    }
                }
                item(key = "note", span = { GridItemSpan(maxLineSpan) }) {
                    GroupFootnote(
                        "A widget is left out on a server that has no reading for it, such as the temperature on some machines.",
                        Modifier.padding(top = 4.dp).animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * A widget on Home, as the page shows it: what it is, and the button that takes it off. [lifted] while
 * it's being dragged. [onShift] moves it a place earlier or later for people who can't drag.
 */
@Composable
private fun PlacedWidget(
    widget: HomeWidget,
    lifted: Boolean,
    onRemove: () -> Unit,
    onShift: (Int) -> Unit,
    first: Boolean,
    last: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (lifted) 1.04f else 1f, label = "lift")
    val actions = Modifier.semantics {
        customActions = listOfNotNull(
            CustomAccessibilityAction("Move earlier") { onShift(-1); true }.takeIf { !first },
            CustomAccessibilityAction("Move later") { onShift(1); true }.takeIf { !last },
        )
    }
    Surface(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }.then(actions),
        shape = RoundedCornerShape(CardRadius),
        color = if (lifted) scheme.surfaceContainerHighest else scheme.surfaceContainer,
        shadowElevation = if (lifted) 8.dp else 0.dp,
    ) {
        val remove: @Composable () -> Unit = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.RemoveCircleOutline, "Remove ${widget.label}", tint = scheme.onSurfaceVariant)
            }
        }
        if (widget.tile) {
            // Taller than a card's row, as a tile is on Home.
            Column(Modifier.heightIn(min = 104.dp).padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(widget.icon, size = 36.dp)
                    Spacer(Modifier.weight(1f))
                    remove()
                }
                Spacer(Modifier.weight(1f))
                Text(widget.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Tile", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        } else {
            Row(Modifier.heightIn(min = 72.dp).padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(widget.icon, size = 36.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                    Text(widget.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(widget.about, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                remove()
            }
        }
    }
}

/**
 * Wraps a widget in the grid. While it's carried it follows the finger above the others, and when it's
 * let go it glides into its place; the rest slide aside as the order changes.
 */
@Composable
private fun LazyGridItemScope.Carried(drag: WidgetDrag, widget: HomeWidget, content: @Composable (lifted: Boolean) -> Unit) {
    val lifted = drag.carried == widget
    val place = when {
        lifted -> Modifier.zIndex(1f).graphicsLayer { translationX = drag.offset.x; translationY = drag.offset.y }
        drag.settling == widget -> Modifier.zIndex(1f).graphicsLayer { translationX = drag.settle.value.x; translationY = drag.settle.value.y }
        else -> Modifier.animateItem()
    }
    Box(place, propagateMinConstraints = true) { content(lifted) }
}

/**
 * Carrying a widget round the grid. The grid lays the widgets out in their order; this follows the finger,
 * and calls [onMove] when the finger comes over another widget, to take that one's place.
 */
@Stable
private class WidgetDrag(
    private val grid: LazyGridState,
    private val scope: CoroutineScope,
    private val onMove: (widget: HomeWidget, target: HomeWidget) -> Unit,
) {
    /** Where the grid measures its items from, within itself: the corner inside its padding. */
    var origin = Offset.Zero

    /** The widget being carried. */
    var carried by mutableStateOf<HomeWidget?>(null)
        private set

    /** The widget last let go, while it glides into place from [settle]. */
    var settling by mutableStateOf<HomeWidget?>(null)
        private set
    val settle = Animatable(Offset.Zero, Offset.VectorConverter)

    private var finger by mutableStateOf(Offset.Zero)

    /** From the carried widget's corner to the finger, so it's held where it was picked up. */
    private var grip = Offset.Zero

    /**
     * Widgets the carried one has just taken the place of, until the finger leaves them. Widgets differ in
     * size, so after a swap the finger can still be over the one swapped with, which would swap them straight back.
     */
    private val passed = mutableSetOf<HomeWidget>()

    private val widgets get() = grid.layoutInfo.visibleItemsInfo.filter { it.key is HomeWidget }

    private fun LazyGridItemInfo.under(point: Offset) =
        point.x >= offset.x && point.x < offset.x + size.width && point.y >= offset.y && point.y < offset.y + size.height

    /** How far the carried widget is from where the grid has it. */
    val offset: Offset
        get() = widgets.firstOrNull { it.key == carried }?.let { finger - grip - it.offset.toOffset() } ?: Offset.Zero

    /** Picks up the widget at [at], a point in the grid. False if there's none there. */
    fun start(at: Offset): Boolean {
        val point = at - origin
        val hit = widgets.firstOrNull { it.under(point) } ?: return false
        finger = point
        grip = point - hit.offset.toOffset()
        passed.clear()
        settling = null
        carried = hit.key as HomeWidget
        return true
    }

    fun drag(by: Offset) {
        val widget = carried ?: return
        finger += by
        val over = widgets.filter { it.under(finger) }.map { it.key as HomeWidget }
        passed.retainAll(over.toSet())
        val target = over.firstOrNull { it != widget && it !in passed } ?: return
        passed += target
        onMove(widget, target)
    }

    fun end() {
        val widget = carried ?: return
        val from = offset
        carried = null
        settling = widget
        // Undispatched, so the widget is at [from] before the next frame is drawn rather than jumping home first.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settle.snapTo(from)
            settle.animateTo(Offset.Zero, spring(stiffness = Spring.StiffnessMediumLow))
            if (settling == widget) settling = null
        }
    }
}
