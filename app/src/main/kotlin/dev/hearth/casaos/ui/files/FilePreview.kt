package dev.hearth.casaos.ui.files

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import dev.hearth.casaos.data.FileEntry
import dev.hearth.casaos.data.PreviewKind
import dev.hearth.casaos.data.previewKind
import dev.hearth.casaos.ui.components.formatBytes

/** A full-screen look at a file on the server: images zoom, text scrolls, video and audio play. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreview(entry: FileEntry, viewModel: FilesViewModel, transfer: Transfer?, onClose: () -> Unit) {
    val kind = previewKind(entry.name) ?: return
    // Media looks best on black; text follows the theme.
    val dark = kind != PreviewKind.Text
    val bg = if (dark) Color.Black else MaterialTheme.colorScheme.background
    val fg = if (dark) Color.White else MaterialTheme.colorScheme.onSurface

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            containerColor = bg,
            topBar = {
                Column {
                    TopAppBar(
                        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = fg) } },
                        title = {
                            Column {
                                Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = fg)
                                Text(formatBytes(entry.size), style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.7f))
                            }
                        },
                        actions = {
                            val idle = transfer == null
                            IconButton(onClick = { viewModel.openWith(entry, share = true) }, enabled = idle) { Icon(Icons.Rounded.Share, "Share", tint = fg) }
                            IconButton(onClick = { viewModel.openWith(entry, share = false) }, enabled = idle) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open with", tint = fg)
                            }
                            IconButton(onClick = { viewModel.download(entry) }, enabled = idle) { Icon(Icons.Rounded.Download, "Download", tint = fg) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = if (dark) Color.Black.copy(alpha = 0.6f) else MaterialTheme.colorScheme.background),
                    )
                    if (transfer != null) {
                        val p = transfer.progress
                        if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), Alignment.Center) {
                when (kind) {
                    PreviewKind.Image -> ImagePreview(viewModel.fileUrl(entry), entry.name)
                    PreviewKind.Text -> TextPreview(entry, viewModel)
                    PreviewKind.Video, PreviewKind.Audio -> MediaPreview(entry, viewModel, fg)
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(url: String, name: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var failed by remember { mutableStateOf(false) }
    if (failed) {
        PreviewMessage("Couldn't show this image", "Try Open with… to view it in another app.", Color.White)
        return
    }
    AsyncImage(
        model = url,
        contentDescription = name,
        contentScale = ContentScale.Fit,
        onError = { failed = true },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    // Double tap toggles between fitting the screen and a 2.5× zoom.
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

@Composable
private fun TextPreview(entry: FileEntry, viewModel: FilesViewModel) {
    val result by produceState<Result<String>?>(null, entry.path) { value = viewModel.readText(entry) }
    val r = result
    when {
        r == null -> CircularProgressIndicator()
        r.isFailure -> PreviewMessage("Couldn't open this file", r.exceptionOrNull()?.message.orEmpty(), MaterialTheme.colorScheme.onSurface)
        else -> Column(Modifier.fillMaxSize()) {
            if (entry.size > TEXT_PREVIEW_BYTES) {
                Text(
                    "Showing the first ${formatBytes(TEXT_PREVIEW_BYTES.toLong())}. Open with… to see all of it.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    r.getOrThrow().ifEmpty { "This file is empty." },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp)
                        .navigationBarsPadding(),
                    softWrap = false,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

/** Plays video or audio with the system player, streamed from the server (seeking fetches only what's needed). */
@Composable
private fun MediaPreview(entry: FileEntry, viewModel: FilesViewModel, fg: Color) {
    val headers by produceState<Result<Map<String, String>>?>(null, entry.path) { value = viewModel.streamHeaders() }
    var error by remember { mutableStateOf(false) }
    val h = headers
    when {
        h == null -> CircularProgressIndicator(color = fg)
        h.isFailure -> PreviewMessage("Couldn't play this file", h.exceptionOrNull()?.message.orEmpty(), fg)
        error -> PreviewMessage("This phone can't play this file", "Try Open with… to play it in another app.", fg)
        else -> {
            val url = remember(entry.path) { viewModel.fileUrl(entry) }
            var view by remember { mutableStateOf<VideoView?>(null) }
            DisposableEffect(Unit) { onDispose { view?.stopPlayback() } }
            Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
                if (previewKind(entry.name) == PreviewKind.Audio) {
                    Text(entry.name, color = fg, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                }
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val controller = MediaController(ctx)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setOnPreparedListener { start(); controller.show(0) }
                            setOnErrorListener { _, _, _ -> error = true; true }
                            setVideoURI(url.toUri(), h.getOrThrow())
                            view = this
                        }
                    },
                    // Audio has no picture: keep the view small so the title above shows, while the controls still appear.
                    modifier = if (previewKind(entry.name) == PreviewKind.Audio) Modifier.size(1.dp) else Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PreviewMessage(title: String, detail: String, color: Color) {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = color, textAlign = TextAlign.Center)
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = color.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
