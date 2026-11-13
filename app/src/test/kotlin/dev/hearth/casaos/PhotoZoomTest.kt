package dev.hearth.casaos

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.hearth.casaos.ui.files.PhotoZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoZoomTest {
    private val box = Size(1000f, 2000f)
    private val portrait = Size(500f, 1000f) // fits the box exactly, at twice the size
    private val landscape = Size(4000f, 2000f) // fitted to 1000 x 500, with black above and below

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
    }

    /** Where a spot of the photo (at [point] in the box when not zoomed) ends up on screen. */
    private fun PhotoZoom.onScreen(point: Offset): Offset {
        val middle = Offset(box.width / 2, box.height / 2)
        return middle + (point - middle) * scale + offset
    }

    @Test fun `a zoomed photo can't be dragged past its edges`() {
        val zoomed = PhotoZoom(scale = 2f)
        assertOffset(Offset(500f, -1000f), zoomed.transformed(1f, Offset(500f, 1000f), Offset(5000f, -5000f), box, portrait).offset)
        // A landscape photo zoomed 2x is twice the box's width but still shorter than it, so it can't move up or down.
        assertOffset(Offset(-500f, 0f), zoomed.transformed(1f, Offset(500f, 1000f), Offset(-5000f, 300f), box, landscape).offset)
    }

    @Test fun `a photo at its normal size stays put`() {
        val moved = PhotoZoom().transformed(1f, Offset(500f, 1000f), Offset(200f, 200f), box, portrait)
        assertEquals(1f, moved.scale)
        assertOffset(Offset.Zero, moved.offset)
        // Pinching smaller than the screen stops at fitting it.
        assertEquals(1f, PhotoZoom().transformed(0.5f, Offset(500f, 1000f), Offset.Zero, box, portrait).scale)
    }

    @Test fun `pinching zooms into the spot between the fingers`() {
        val fingers = Offset(800f, 600f)
        val before = PhotoZoom(scale = 2f, offset = Offset(-100f, 200f))
        val spot = Offset(
            (fingers.x - 500f - before.offset.x) / before.scale + 500f,
            (fingers.y - 1000f - before.offset.y) / before.scale + 1000f,
        )
        assertOffset(fingers, before.onScreen(spot))
        val after = before.transformed(1.5f, fingers, Offset.Zero, box, portrait)
        assertEquals(3f, after.scale, 0.001f)
        assertOffset(fingers, after.onScreen(spot))
        // Moving the fingers while pinching carries that spot along with them.
        val dragged = before.transformed(1.5f, fingers, Offset(-40f, 30f), box, portrait)
        assertOffset(fingers + Offset(-40f, 30f), dragged.onScreen(spot))
    }

    @Test fun `double tap zooms in on the spot tapped, and back out`() {
        val tap = Offset(700f, 1200f)
        val zoomed = PhotoZoom().toggled(tap, box, portrait)
        assertEquals(2.5f, zoomed.scale)
        assertOffset(tap, zoomed.onScreen(tap))
        assertEquals(PhotoZoom(), zoomed.toggled(tap, box, portrait))
        // Tapping the black above a landscape photo zooms toward that side, but zoomed it's still shorter than the
        // screen, so it stays in the middle rather than leaving a gap.
        assertOffset(Offset(-735f, 0f), PhotoZoom().toggled(Offset(990f, 10f), box, landscape).offset)
    }

    @Test fun `a sideways swipe is passed on once the photo is at that edge`() {
        val middle = PhotoZoom(scale = 2f)
        assertFalse(middle.atSideEdge(-10f, box, portrait))
        assertFalse(middle.atSideEdge(10f, box, portrait))

        // Dragged all the way left, the photo shows its right edge: swiping left again moves to the next photo,
        // swiping right pans back.
        val rightEdge = middle.transformed(1f, Offset(500f, 1000f), Offset(-5000f, 0f), box, portrait)
        assertTrue(rightEdge.atSideEdge(-10f, box, portrait))
        assertFalse(rightEdge.atSideEdge(10f, box, portrait))

        // A portrait photo zoomed only a little is still narrower than the screen, so it has no room to pan sideways.
        val tall = Size(250f, 1000f)
        assertTrue(PhotoZoom(scale = 1.5f).atSideEdge(-10f, box, tall))
        assertTrue(PhotoZoom(scale = 1.5f).atSideEdge(10f, box, tall))
    }

    @Test fun `before the photo has loaded it's taken to fill the screen`() {
        val zoomed = PhotoZoom(scale = 2f).transformed(1f, Offset(500f, 1000f), Offset(5000f, 5000f), box, Size.Unspecified)
        assertOffset(Offset(500f, 1000f), zoomed.offset)
    }
}
