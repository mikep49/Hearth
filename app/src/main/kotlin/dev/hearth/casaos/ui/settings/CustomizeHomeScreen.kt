package dev.hearth.casaos.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.rounded.UnfoldMore
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex
import dev.hearth.casaos.data.HomeLayout
import dev.hearth.casaos.data.HomeSize
import dev.hearth.casaos.data.HomeWidget
import dev.hearth.casaos.ui.components.BackButton
import dev.hearth.casaos.ui.components.CardRadius
import dev.hearth.casaos.ui.components.Chevron
import dev.hearth.casaos.ui.components.GroupFootnote
import dev.hearth.casaos.ui.components.IconTile
import dev.hearth.casaos.ui.components.ListGroup
import dev.hearth.casaos.ui.components.ListRow
import dev.hearth.casaos.ui.components.ReadableGutter
import dev.hearth.casaos.ui.components.SectionHeader
import dev.hearth.casaos.ui.components.SwitchRow
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

/**
 * What the row that opens the page says: how much of what there is has been put on Home, and whether
 * that's this server's [own] arrangement.
 */
internal fun homeWidgetsSummary(shown: List<HomeWidget>, own: Boolean = false): String {
    val count = when (shown.size) {
        0 -> "Nothing on Home"
        HomeWidget.entries.size -> "All ${shown.size} widgets"
        else -> "${shown.size} of ${HomeWidget.entries.size} widgets"
    }
    return if (own) "$count · this server only" else count
}

/** The row on the Appearance page that opens [CustomizeHomeScreen]. */
@Composable
fun CustomizeHomeRow(shown: List<HomeWidget>, own: Boolean, onOpen: () -> Unit) {
    SectionHeader("Home")
    ListGroup {
        ListRow(
            "Customize Home",
            subtitle = homeWidgetsSummary(shown, own),
            icon = Icons.Rounded.DashboardCustomize,
            trailing = { Chevron() },
            onClick = onOpen,
        )
    }
}

private val Side = 20.dp
private val Gap = 12.dp

/**
 * Customize Home, opened from Home and from Settings › Appearance: Home's widgets at the sizes they have
 * there, small ones two to a row and the rest across it. Holding one picks it up to drag it somewhere
 * else, its size button gives it more or less room, its minus button takes it off Home, and the ones
 * taken off wait underneath to be put back. [onChange] saves each change as it's made.
 *
 * [layout] is the one servers share unless this server ([serverLabel]) has its own, which is what
 * [ownLayout] says and the switch at the top changes through [onOwnLayout]. Null leaves the switch out,
 * for when there's no other server to differ from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeHomeScreen(
    layout: HomeLayout,
    onChange: (HomeLayout) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    ownLayout: Boolean? = null,
    onOwnLayout: (Boolean) -> Unit = {},
    serverLabel: String = "",
) {
    val shown = layout.widgets
    val hidden = HomeWidget.entries.filter { it !in shown }
    val haptic = LocalHapticFeedback.current
    // A drag outlives the layout it started with, so it moves widgets in whatever the layout is by then.
    val latest by rememberUpdatedState(layout)
    val change by rememberUpdatedState(onChange)

    BackHandler(onBack = onClose)

    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val drag = remember(grid, scope) {
        WidgetDrag(grid, scope) { widget, target ->
            // The grid follows its first visible row when that row moves, which would scroll the page mid-drag.
            grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
            change(latest.moved(widget, target))
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
                    TextButton(onClick = { onChange(HomeLayout.Default) }, enabled = layout != HomeLayout.Default) { Text("Reset") }
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
                if (ownLayout != null) {
                    item(key = "own", span = { GridItemSpan(maxLineSpan) }) {
                        ListGroup(Modifier.animateItem()) {
                            SwitchRow(
                                "Own layout for this server",
                                checked = ownLayout,
                                onCheckedChange = onOwnLayout,
                                subtitle = if (ownLayout) "Only $serverLabel shows this layout" else "Using the layout your servers share",
                                icon = Icons.Rounded.Dns,
                            )
                        }
                    }
                }
                item(key = "intro", span = { GridItemSpan(maxLineSpan) }) {
                    GroupFootnote(
                        if (shown.isEmpty()) "Nothing is on Home. Add widgets from the list below."
                        else "Touch and hold a widget to drag it somewhere else. Tap its size to give it more or less room.",
                        Modifier.padding(bottom = 4.dp).animateItem(),
                    )
                }
                items(shown, key = { it }, span = { GridItemSpan(if (layout.isSmall(it)) 1 else maxLineSpan) }) { widget ->
                    Carried(drag, widget) { lifted ->
                        PlacedWidget(
                            widget,
                            layout.sizeOf(widget),
                            lifted,
                            onResize = {
                                onChange(layout.resized(widget, layout.nextSize(widget)))
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            },
                            onRemove = { onChange(layout.removing(widget)) },
                            onShift = { by -> onChange(layout.shifted(widget, by)) },
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
                            onClick = { onChange(layout.adding(widget)) },
                        )
                    }
                }
                item(key = "note", span = { GridItemSpan(maxLineSpan) }) {
                    GroupFootnote(
                        "On Home, a small widget with none beside it fills its row. " +
                            "A widget is left out on a server that has no reading for it, such as the temperature on some machines.",
                        Modifier.padding(top = 4.dp).animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * A widget on Home, as the page shows it: what it is, its [size], and the button that takes it off. A small
 * one is a square tile, and a large one is taller than a medium one, as they are on Home. [lifted] while
 * it's being dragged. [onShift] moves it a place earlier or later for people who can't drag.
 */
@Composable
private fun PlacedWidget(
    widget: HomeWidget,
    size: HomeSize,
    lifted: Boolean,
    onResize: () -> Unit,
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
        // Under the name: the size, which a tap changes, or what the widget shows if it comes in one size only.
        val detail: @Composable () -> Unit = {
            if (widget.resizable) {
                SizeButton(widget, size, onResize)
            } else {
                Text(widget.about, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (size == HomeSize.Small) {
            Column(Modifier.heightIn(min = 132.dp).padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(widget.icon, size = 36.dp)
                    Spacer(Modifier.weight(1f))
                    remove()
                }
                Spacer(Modifier.weight(1f))
                Text(widget.label, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail()
            }
        } else {
            Row(
                Modifier.heightIn(min = if (size == HomeSize.Large) 132.dp else 76.dp).padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(widget.icon, size = 36.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(if (widget.resizable) 6.dp else 0.dp)) {
                    Text(widget.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    detail()
                }
                remove()
            }
        }
    }
}

/** The pill under a widget's name that says its size. Tapping it gives the widget the next size up, then back to the smallest. */
@Composable
private fun SizeButton(widget: HomeWidget, size: HomeSize, onResize: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(CircleShape)
            .background(scheme.primary.copy(alpha = 0.14f))
            .clickable(onClickLabel = "change size", role = Role.Button, onClick = onResize)
            .semantics { contentDescription = "${widget.label} size, ${size.label}" }
            .heightIn(min = 32.dp)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(size.label, style = MaterialTheme.typography.labelLarge, color = scheme.primary, maxLines = 1)
        Icon(Icons.Rounded.UnfoldMore, null, Modifier.padding(start = 2.dp).size(16.dp).rotate(45f), tint = scheme.primary)
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
