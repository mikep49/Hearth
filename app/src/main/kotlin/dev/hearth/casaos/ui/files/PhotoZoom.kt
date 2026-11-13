package dev.hearth.casaos.ui.files

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import kotlin.math.max
import kotlin.math.min

/**
 * How far a full-screen photo is zoomed in and moved: [offset] is how far the photo's middle sits from the middle
 * of the space it's shown in, in pixels. Every change takes that space ([box]) and the photo's own size ([image],
 * fitted inside the box; unspecified until the photo has loaded), and keeps the photo's edges from coming inside
 * the box's, so a zoomed photo can't be dragged off the screen.
 */
internal data class PhotoZoom(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    val zoomed: Boolean get() = scale > 1f

    /** Zooms by [factor] keeping the spot under [focus] (a point in the box) still, then moves the photo by [pan]. */
    fun transformed(factor: Float, focus: Offset, pan: Offset, box: Size, image: Size): PhotoZoom {
        val newScale = (scale * factor).coerceIn(1f, MAX_ZOOM)
        val fromMiddle = focus - Offset(box.width / 2, box.height / 2)
        return PhotoZoom(newScale, (offset - fromMiddle) * (newScale / scale) + fromMiddle + pan).clamped(box, image)
    }

    /** Double tap: zooms in on [focus], or back out to fit the screen. */
    fun toggled(focus: Offset, box: Size, image: Size): PhotoZoom =
        if (zoomed) PhotoZoom() else PhotoZoom().transformed(DOUBLE_TAP_ZOOM, focus, Offset.Zero, box, image)

    fun clamped(box: Size, image: Size): PhotoZoom {
        val limit = panLimit(box, image)
        return copy(offset = Offset(offset.x.coerceIn(-limit.x, limit.x), offset.y.coerceIn(-limit.y, limit.y)))
    }

    /**
     * Whether the photo is already as far as it goes for a finger moving [dx] sideways (left when negative), so the
     * swipe is better passed on to the album to show the next photo.
     */
    fun atSideEdge(dx: Float, box: Size, image: Size): Boolean {
        val limit = panLimit(box, image).x
        return if (dx < 0) offset.x <= -limit + EDGE_SLACK else offset.x >= limit - EDGE_SLACK
    }

    /** A step [t] (0 to 1) of the way from here to [to], for animating a double tap. */
    fun towards(to: PhotoZoom, t: Float) = PhotoZoom(scale + (to.scale - scale) * t, offset + (to.offset - offset) * t)

    /** How far the photo's middle may move each way before one of its edges comes inside the box. */
    private fun panLimit(box: Size, image: Size): Offset {
        val shown = fitted(box, image) * scale
        return Offset(max(0f, (shown.width - box.width) / 2), max(0f, (shown.height - box.height) / 2))
    }

    private companion object {
        const val MAX_ZOOM = 6f
        const val DOUBLE_TAP_ZOOM = 2.5f
        /** Rounding leaves a photo pushed to its edge a hair short of it. */
        const val EDGE_SLACK = 1f

        /** The size the photo is drawn at to fit the box, as `ContentScale.Fit` draws it. */
        fun fitted(box: Size, image: Size): Size {
            if (!image.isSpecified || image.width <= 0f || image.height <= 0f) return box
            return image * min(box.width / image.width, box.height / image.height)
        }
    }
}
