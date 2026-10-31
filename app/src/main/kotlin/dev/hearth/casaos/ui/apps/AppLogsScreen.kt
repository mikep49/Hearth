package dev.hearth.casaos.ui.apps

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dev.hearth.casaos.data.CasaClient
import dev.hearth.casaos.data.CasaException
import dev.hearth.casaos.data.LogHistory
import dev.hearth.casaos.data.filterLogLines
import dev.hearth.casaos.data.logLines
import dev.hearth.casaos.data.matchRanges
import dev.hearth.casaos.data.newestLinesWithin
import dev.hearth.casaos.ui.components.EmptyState
import dev.hearth.casaos.ui.components.SearchField
import dev.hearth.casaos.ui.components.StatusDot
import dev.hearth.casaos.ui.theme.hearth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

data class AppLogsUi(
    val lines: List<String>? = null,
    val loading: Boolean = false,
    /** Why the last load failed. With [lines] still shown, it was a refresh that failed. */
    val error: String? = null,
    val history: LogHistory = LogHistory.Medium,
    /** Reload on its own every few seconds while the page is on screen. */
    val live: Boolean = true,
    val wrap: Boolean = true,
    val updatedAt: Long? = null,
)

class AppLogsViewModel(private val client: CasaClient, private val appId: String) : ViewModel() {
    var ui by mutableStateOf(AppLogsUi())
        private set

    private var raw: String? = null
    private var job: Job? = null

    /** Reloads every [LIVE_INTERVAL_MS] while live; call it only while the page is visible. */
    suspend fun poll() {
        while (true) {
            if (ui.live) {
                refresh()
                job?.join()
            }
            delay(LIVE_INTERVAL_MS)
        }
    }

    fun refresh() {
        if (job?.isActive == true) return
        job = viewModelScope.launch { fetch() }
    }

    fun setLive(live: Boolean) {
        ui = ui.copy(live = live)
    }

    fun setWrap(wrap: Boolean) {
        ui = ui.copy(wrap = wrap)
    }

    fun setHistory(history: LogHistory) {
        if (history == ui.history) return
        ui = ui.copy(history = history)
        job?.cancel()
        job = viewModelScope.launch { fetch() }
    }

    private suspend fun fetch() {
        val history = ui.history
        ui = ui.copy(loading = true)
        try {
            val text = client.appLogs(appId, history.lines)
            // Nothing new: keep the same list so the page doesn't redraw or jump.
            val lines = if (text == raw && ui.lines != null) ui.lines else withContext(Dispatchers.Default) { logLines(text) }
            if (history == ui.history) {
                raw = text
                ui = ui.copy(lines = lines, error = null, updatedAt = System.currentTimeMillis())
            }
        } catch (e: CasaException) {
            ui = ui.copy(error = e.message)
        } finally {
            ui = ui.copy(loading = false)
        }
    }

    private companion object {
        const val LIVE_INTERVAL_MS = 3_000L
    }
}

// The clipboard travels through a channel that fails above about 1 MB, and a char is two bytes.
private const val CLIPBOARD_MAX_CHARS = 300_000

// Without wrapping, the page scrolls sideways to the longest line, up to this many characters.
private const val MAX_LINE_CHARS = 1_000

private val LogBackground = Color(0xFF07080B)
private val LogText = Color(0xFFC9D1D9)
private val LogMuted = Color(0xFF8A90A0)
private val MatchBackground = Color(0x66E3B341)
private val LogStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = LogText)

/** An app's container logs, full screen: live updates, search, copy and share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogsScreen(
    viewModel: AppLogsViewModel,
    appId: String,
    appName: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = viewModel.ui
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current

    // Loads on open (the view model outlives the page, so this also replaces what it showed last time),
    // then keeps going while live and the app is in the foreground.
    LaunchedEffect(viewModel, owner) {
        viewModel.refresh()
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.poll() }
    }

    BackHandler(enabled = query.isNotEmpty()) { query = "" }
    BackHandler(enabled = query.isEmpty(), onBack = onClose)

    val q = query.trim()
    val all = ui.lines
    val shown = remember(all, q) { all?.let { filterLogLines(it, q) } }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") } },
                title = {
                    Column {
                        Text(appName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ui.live) StatusDot(MaterialTheme.hearth.good, Modifier.padding(end = 6.dp), pulse = true)
                            Text(
                                subtitle(ui),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (ui.live) {
                        IconButton(onClick = { viewModel.setLive(false) }) { Icon(Icons.Rounded.Pause, "Pause live updates") }
                    } else {
                        IconButton(onClick = { viewModel.setLive(true) }) { Icon(Icons.Rounded.PlayArrow, "Resume live updates") }
                        Box(Modifier.size(48.dp), Alignment.Center) {
                            if (ui.loading) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                            } else {
                                IconButton(onClick = viewModel::refresh) { Icon(Icons.Rounded.Refresh, "Refresh") }
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val lines = shown.orEmpty()
                            DropdownMenuItem(
                                text = { Text(if (q.isEmpty()) "Copy" else "Copy matching lines") },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                enabled = lines.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    val note = copyLines(context, appName, lines)
                                    if (note != null) scope.launch { snackbar.showSnackbar(note) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (q.isEmpty()) "Share" else "Share matching lines") },
                                leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                enabled = lines.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        val note = shareLines(context, appId, appName, lines)
                                        if (note != null) snackbar.showSnackbar(note)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Wrap lines") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.WrapText, null) },
                                trailingIcon = { if (ui.wrap) Icon(Icons.Rounded.Check, null) },
                                onClick = { menu = false; viewModel.setWrap(!ui.wrap) },
                            )
                            HorizontalDivider()
                            LogHistory.entries.forEach { h ->
                                DropdownMenuItem(
                                    text = { Text(h.label) },
                                    trailingIcon = { if (ui.history == h) Icon(Icons.Rounded.Check, null) },
                                    onClick = { menu = false; viewModel.setHistory(h) },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            SearchField(
                query, { query = it }, "Search logs",
                Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp),
            )
            if (q.isNotEmpty() && shown != null && all != null) {
                Text(
                    "${"%,d".format(shown.size)} of ${"%,d".format(all.size)} lines match",
                    Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // A failed refresh keeps the last logs on screen and says so above them.
            if (ui.error != null && all != null) {
                Text(
                    "Couldn't refresh: ${ui.error}",
                    Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(LogBackground)) {
                when {
                    all == null && ui.error == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    all == null -> EmptyState("Couldn't load logs", ui.error.orEmpty()) {
                        TextButton(onClick = viewModel::refresh) { Text("Try again") }
                    }
                    all.isEmpty() -> Text("No log output yet.", Modifier.align(Alignment.Center), color = LogMuted)
                    shown.isNullOrEmpty() -> Text("No lines match \"$q\".", Modifier.align(Alignment.Center), color = LogMuted)
                    else -> LogList(shown, q, ui.wrap)
                }
            }
        }
    }
}

private fun subtitle(ui: AppLogsUi): String {
    val updated = ui.updatedAt?.let { DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it)) }
    return when {
        ui.live -> "Live · ${ui.history.label}"
        updated != null -> "Paused · Updated $updated"
        else -> "Paused"
    }
}

@Composable
private fun LogList(lines: List<String>, query: String, wrap: Boolean) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Stays pinned to the newest line until you scroll up, and again once you're back at the bottom.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling -> if (!scrolling) follow = !list.canScrollForward }
    }
    LaunchedEffect(query) { follow = true }
    LaunchedEffect(lines, wrap, follow) { if (follow) list.scrollToItem(lines.lastIndex) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // Monospace, so the widest line is just its length times one character's width.
        val width = if (wrap) maxWidth else remember(lines, maxWidth) {
            val charPx = measurer.measure("0", LogStyle).size.width
            val longest = lines.maxOf { it.length }.coerceAtMost(MAX_LINE_CHARS)
            maxOf(maxWidth, with(density) { (charPx * longest).toDp() } + 28.dp)
        }
        val sideways = if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())
        SelectionContainer(sideways) {
            LazyColumn(
                Modifier.width(width).fillMaxHeight(),
                state = list,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                itemsIndexed(lines, contentType = { _, _ -> "line" }) { _, line ->
                    Text(
                        highlighted(line, query),
                        style = LogStyle,
                        softWrap = wrap,
                        maxLines = if (wrap) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
        if (!follow) {
            SmallFloatingActionButton(
                onClick = { follow = true; scope.launch { list.scrollToItem(lines.lastIndex) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Icon(Icons.Rounded.ArrowDownward, "Jump to newest") }
        }
    }
}

private fun highlighted(line: String, query: String): AnnotatedString {
    val ranges = matchRanges(line, query)
    if (ranges.isEmpty()) return AnnotatedString(line)
    return buildAnnotatedString {
        append(line)
        ranges.forEach { addStyle(SpanStyle(background = MatchBackground, color = Color.White), it.first, it.last + 1) }
    }
}

/** Copies [lines], or as many of the newest as the clipboard takes. Returns a note to show, if any. */
private fun copyLines(context: Context, appName: String, lines: List<String>): String? {
    val (text, kept) = newestLinesWithin(lines, CLIPBOARD_MAX_CHARS)
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return "Couldn't reach the clipboard"
    clipboard.setPrimaryClip(ClipData.newPlainText("$appName logs", text))
    return when {
        kept < lines.size -> "Copied the newest ${"%,d".format(kept)} lines. Share to send them all."
        // Android 13 and later confirm a copy on their own.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "Copied ${"%,d".format(kept)} lines"
        else -> null
    }
}

/** Shares [lines] as a text file, which has no size limit. Returns a note to show, if any. */
private suspend fun shareLines(context: Context, appId: String, appName: String, lines: List<String>): String? {
    val uri = withContext(Dispatchers.IO) {
        // Under the cache folder the file provider already serves for shared server files.
        val dir = File(context.cacheDir, "open/logs").apply { mkdirs() }
        val file = File(dir, "${appId.replace(Regex("[^A-Za-z0-9._-]"), "_")}-logs.txt")
        file.bufferedWriter().use { w -> lines.forEach { w.write(it); w.write("\n") } }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "$appName logs")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // Through the chooser, the grant only reaches the picked app if the file is in the intent's clip data.
    send.clipData = ClipData.newRawUri("$appName logs", uri)
    return try {
        context.startActivity(Intent.createChooser(send, "Share $appName logs").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        null
    } catch (_: ActivityNotFoundException) {
        "No app on this phone can share text files"
    }
}
