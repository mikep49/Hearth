package dev.hearth.casaos.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.INDEX_MAX_ENTRIES
import dev.hearth.casaos.files.PhotoThumbnail
import dev.hearth.casaos.ui.components.BackButton
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.hearthTopBarColors
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** The narrowest a photo in the gallery gets before the grid drops a column: four across on most phones. */
private val PhotoMinWidth = 84.dp
private val PhotoGap = 3.dp
private val PhotoShape = RoundedCornerShape(6.dp)

private val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun monthTitle(month: YearMonth?): String = month?.format(monthFormat) ?: "No date"

private fun photoCount(n: Int): String = if (n == 1) "1 photo" else "%,d photos".format(n)

/**
 * The gallery's top bar: Back returns to the folders. While photos are ticked it counts them, and offers to
 * tick them all or none, as the Files header does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryBar(
    gallery: GalleryState,
    folder: String,
    side: Dp,
    selectedCount: Int,
    onBack: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    val count = gallery.photos.size
    TopAppBar(
        navigationIcon = { BackButton(onBack) },
        title = {
            Column {
                Text(if (selectedCount > 0) "$selectedCount selected" else "Gallery", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        count > 0 -> "${photoCount(count)} in $folder"
                        gallery.scanning -> "Looking in $folder"
                        else -> folder
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            if (selectedCount > 0) {
                IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, "Select all") }
                IconButton(onClick = onClearSelection) { Icon(Icons.Rounded.Close, "Clear selection") }
            }
        },
        colors = hearthTopBarColors(),
        // The tab is already clear of the status bar.
        windowInsets = WindowInsets(0, 0, 0, 0),
        // The bar insets its buttons by 4dp and they pad their icons by 12dp: this lines the icons up with the gutter.
        modifier = Modifier.padding(horizontal = (side - 16.dp).coerceAtLeast(0.dp)),
    )
}

/**
 * The gallery's photos, a month at a time. Tap opens a photo full screen; long-press ticks it, and while
 * [selecting] a tap ticks too, as in the folder views.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryGrid(
    gallery: GalleryState,
    folder: String,
    side: Dp,
    thumbnail: (FileEntry) -> PhotoThumbnail?,
    selecting: Boolean,
    isSelected: (FileEntry) -> Boolean,
    onOpen: (FileEntry) -> Unit,
    onSelect: (FileEntry) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        gallery.months.isNotEmpty() -> PullToRefreshBox(isRefreshing = gallery.scanning, onRefresh = onRefresh, modifier = modifier) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(PhotoMinWidth),
                // Room at the bottom so the last row isn't under the selection's actions.
                contentPadding = PaddingValues(start = side, end = side, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(PhotoGap),
                horizontalArrangement = Arrangement.spacedBy(PhotoGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                val wide: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
                gallery.months.forEach { month ->
                    item(key = "month:${month.month}", span = wide, contentType = "month") {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(monthTitle(month.month), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text(photoCount(month.photos.size), style = MaterialTheme.typography.labelMedium, color = muted)
                        }
                    }
                    items(month.photos, key = { it.path }, contentType = { "photo" }) { entry ->
                        GalleryPhoto(
                            entry,
                            thumbnail = thumbnail(entry),
                            selecting = selecting,
                            selected = isSelected(entry),
                            onClick = { onOpen(entry) },
                            onSelect = { onSelect(entry) },
                        )
                    }
                }
                if (gallery.truncated) {
                    item(key = "truncated", span = wide, contentType = "note") {
                        Text(
                            "There are a lot of files here: only the first ${"%,d".format(INDEX_MAX_ENTRIES)} were checked for photos.",
                            Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                        )
                    }
                }
            }
        }
        gallery.scanning -> Column(modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Looking for photos…", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleMedium)
            val scanned = gallery.foldersScanned
            if (scanned > 0) {
                Text(
                    if (scanned == 1) "1 folder checked" else "%,d folders checked".format(scanned),
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }
        }
        gallery.error != null -> EmptyState("Couldn't look for photos", gallery.error, modifier, icon = Icons.Rounded.CloudOff) {
            TextButton(onClick = onRefresh) { Text("Try again") }
        }
        else -> EmptyState(
            "No photos here",
            "There are no photos in $folder or the folders inside it.",
            modifier,
            icon = Icons.Rounded.PhotoLibrary,
        )
    }
}

/** One photo in the gallery, cropped square. One too big for a thumbnail shows as an icon, and still opens. */
@Composable
private fun GalleryPhoto(
    entry: FileEntry,
    thumbnail: PhotoThumbnail?,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
) {
    // Whether the photo is showing, rather than the icon that stands in for it.
    var pictured by remember(thumbnail?.url) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(PhotoShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelect()
                },
            )
            .semantics {
                contentDescription = entry.name
                if (selecting) this.selected = selected
            },
    ) {
        Thumbnail(entry, thumbnail, pictured, onPictured = { pictured = it }, Modifier.matchParentSize())
        if (selected) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
        if (selecting) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { TickMark(selected, pictured) }
    }
}
