package dev.hearth.casaos.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hearth.casaos.data.AppState
import dev.hearth.casaos.data.HomeSize
import dev.hearth.casaos.data.InstalledApp
import dev.hearth.casaos.data.shortcutSlots
import dev.hearth.casaos.ui.components.AppIcon
import dev.hearth.casaos.ui.components.HearthCard
import dev.hearth.casaos.ui.components.SectionLabel
import dev.hearth.casaos.ui.components.adaptiveColumns

/** The narrowest an app's shortcut gets on a medium or large widget, its name included, before a row fits one fewer. */
private val ShortcutMinWidth = 72.dp

/** One of the widget's places: an app, the button for the apps that don't fit, or a blank while the apps are read. */
private sealed interface Slot {
    data class App(val app: InstalledApp) : Slot
    data class More(val count: Int) : Slot
    data object Loading : Slot
}

/**
 * The App shortcuts widget: [apps] to open with a tap, or null while they're being read. A [HomeSize.Small]
 * one is a square tile with four icons; a medium one is a row of icons with their names, as many as fit
 * across; a large one shows every app, a row after another. When they don't all fit, the last place is a
 * button that says how many more there are, and [onMore] shows them all. [starting] are apps being started.
 *
 * A small one is laid out without measuring its width first, as tiles in a row are asked how tall they'd be.
 */
@Composable
internal fun AppShortcutsWidget(
    size: HomeSize,
    apps: List<InstalledApp>?,
    starting: Set<String>,
    onOpen: (InstalledApp) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier,
) {
    HearthCard(modifier) {
        if (size == HomeSize.Small) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(TilePadding)) {
                SectionLabel("Apps")
                ShortcutGrid(
                    slots(apps, places = 4, loading = 4), columns = 2, names = false, iconSize = 44.dp, starting, onOpen, onMore,
                    Modifier.weight(1f).padding(top = 8.dp),
                )
            }
        } else {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 10.dp)) {
                SectionLabel("Apps", Modifier.padding(start = 12.dp))
                BoxWithConstraints(Modifier.padding(top = 6.dp)) {
                    val columns = adaptiveColumns(maxWidth, ShortcutMinWidth, 0.dp)
                    val places = if (size == HomeSize.Large) Int.MAX_VALUE else columns
                    ShortcutGrid(slots(apps, places, loading = columns), columns, names = true, iconSize = 48.dp, starting, onOpen, onMore)
                }
            }
        }
    }
}

/** What goes in each of up to [places] places: blanks, [loading] of them, until the apps have been read. */
private fun slots(apps: List<InstalledApp>?, places: Int, loading: Int): List<Slot> {
    if (apps == null) return List(minOf(places, loading)) { Slot.Loading }
    val (shown, more) = shortcutSlots(apps.size, places)
    return apps.take(shown).map { Slot.App(it) } + listOfNotNull(Slot.More(more).takeIf { more > 0 })
}

/** [slots] in rows of [columns], the last row short if it has to be, with each app's name under it if [names]. */
@Composable
private fun ShortcutGrid(
    slots: List<Slot>,
    columns: Int,
    names: Boolean,
    iconSize: Dp,
    starting: Set<String>,
    onOpen: (InstalledApp) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.SpaceEvenly) {
        slots.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { slot ->
                    val place = Modifier.weight(1f)
                    when (slot) {
                        is Slot.App -> Shortcut(slot.app, names, iconSize, slot.app.id in starting, onOpen, place)
                        is Slot.More -> MoreShortcuts(slot.count, names, iconSize, onMore, place)
                        Slot.Loading -> Blank(names, iconSize, place)
                    }
                }
                // Places left empty at the end of a short row keep the others in their columns.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** An app's icon, and its name under it if [names]. A stopped app is greyed out, as on the Apps tab. */
@Composable
private fun Shortcut(app: InstalledApp, names: Boolean, iconSize: Dp, busy: Boolean, onOpen: (InstalledApp) -> Unit, modifier: Modifier) {
    val running = app.state == AppState.Running
    ShortcutPlace(
        names = names,
        onClick = { onOpen(app) },
        modifier = modifier.semantics {
            if (!names) contentDescription = app.name
            when {
                busy -> stateDescription = "Starting"
                app.state == AppState.Stopped -> stateDescription = "Stopped"
                !running -> stateDescription = "Not running"
            }
        },
        icon = {
            Box(contentAlignment = Alignment.Center) {
                AppIcon(app.iconUrl, app.name, Modifier.alpha(if (running) 1f else 0.45f), size = iconSize, grayscale = !running)
                if (busy) CircularProgressIndicator(Modifier.size(iconSize / 2), strokeWidth = 2.5.dp)
            }
        },
        name = app.name,
        dimmed = !running,
    )
}

/** The last place when the apps don't all fit: how many more there are. */
@Composable
private fun MoreShortcuts(count: Int, names: Boolean, iconSize: Dp, onMore: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    ShortcutPlace(
        names = names,
        onClick = onMore,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = if (count == 1) "1 more app" else "$count more apps"
            role = Role.Button
            onClick("show all apps") { onMore(); true }
        },
        icon = {
            Box(
                Modifier.size(iconSize).clip(RoundedCornerShape(iconSize * 0.28f)).background(scheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("+$count", style = MaterialTheme.typography.titleMedium, color = scheme.primary, maxLines = 1)
            }
        },
        name = "More",
    )
}

/** Where an app will be once the apps have been read. */
@Composable
private fun Blank(names: Boolean, iconSize: Dp, modifier: Modifier) {
    ShortcutPlace(
        names = names,
        onClick = null,
        modifier = modifier.clearAndSetSemantics {},
        icon = { Box(Modifier.size(iconSize).clip(RoundedCornerShape(iconSize * 0.28f)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) },
        // Keeps the line for the name, so the widget doesn't change height when the apps come in.
        name = " ",
    )
}

/** A place on the widget: [icon], with [name] under it if [names], and the whole place to tap. */
@Composable
private fun ShortcutPlace(
    names: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier,
    icon: @Composable () -> Unit,
    name: String,
    dimmed: Boolean = false,
) {
    val tap = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).then(tap).padding(horizontal = 4.dp, vertical = if (names) 8.dp else 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        if (names) {
            Text(
                name,
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
