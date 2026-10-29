package dev.hearth.casaos.ui.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.hearth.casaos.WebAppActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puts an app's web UI on the home screen. The icon opens it in [WebAppActivity], a full-screen window of its
 * own, the way an installed web app (PWA) would open.
 *
 * Returns false when the launcher can't take pinned shortcuts. Otherwise the system asks the user to confirm.
 */
suspend fun addToHomeScreen(context: Context, name: String, url: String, iconUrl: String?): Boolean {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
    val logo = iconUrl?.let { loadBitmap(context, it) }
    val icon = withContext(Dispatchers.Default) { if (logo != null) logoIcon(logo) else letterIcon(name) }
    val shortcut = ShortcutInfoCompat.Builder(context, "web:$url")
        .setShortLabel(name.ifBlank { url.toUri().host.orEmpty() })
        .setIcon(IconCompat.createWithAdaptiveBitmap(icon))
        .setIntent(WebAppActivity.intent(context, name, url))
        .build()
    return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
}

/** [addToHomeScreen] for a button: runs in the background and says so if the launcher can't do it. */
@Composable
fun rememberAddToHomeScreen(): (name: String, url: String, iconUrl: String?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        var working = false
        { name, url, iconUrl ->
            // Fetching the icon takes a moment; a second tap meanwhile would ask the user twice.
            if (!working) {
                working = true
                scope.launch {
                    try {
                        if (!addToHomeScreen(context, name, url, iconUrl)) {
                            Toast.makeText(context, "Your home screen app doesn't support adding icons", Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        working = false
                    }
                }
            }
        }
    }
}

private suspend fun loadBitmap(context: Context, url: String): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(LOGO_PX)
        // Software bitmaps, since the icon is drawn onto a canvas.
        .allowHardware(false)
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
    return result.image.toBitmap()
}

// Adaptive icons are 108dp with the outer 18dp on each side masked off; this is that canvas at xxxhdpi.
private const val ICON_PX = 432
private const val LOGO_PX = 256

/** The app's logo, centred on white and sized to stay inside the launcher's mask, whatever its shape. */
private fun logoIcon(logo: Bitmap): Bitmap {
    val out = createBitmap(ICON_PX, ICON_PX)
    val canvas = Canvas(out)
    canvas.drawColor(Color.WHITE)
    val side = ICON_PX * 0.46f
    val scale = side / maxOf(logo.width, logo.height)
    val w = logo.width * scale
    val h = logo.height * scale
    val dst = RectF((ICON_PX - w) / 2, (ICON_PX - h) / 2, (ICON_PX + w) / 2, (ICON_PX + h) / 2)
    canvas.drawBitmap(logo, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    return out
}

/** For apps without a usable logo: the first letter of the name in Hearth's colours. */
private fun letterIcon(name: String): Bitmap {
    val out = createBitmap(ICON_PX, ICON_PX)
    val canvas = Canvas(out)
    canvas.drawColor(0xFF10131A.toInt())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF8A4C.toInt()
        textSize = ICON_PX * 0.32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val letter = name.trim().firstOrNull()?.uppercase() ?: "?"
    val baseline = ICON_PX / 2f - (paint.descent() + paint.ascent()) / 2
    canvas.drawText(letter, ICON_PX / 2f, baseline, paint)
    return out
}
