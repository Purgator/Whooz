package fr.arichard.whooz.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A face cropped out of the FILL_CENTER preview must never become a player. */
class FaceClipTest {
    private val w = 1080
    private val h = 2400

    @Test
    fun centreInsideTheViewIsVisible() {
        assertTrue(FaceClip.isVisible(540f, 1200f, 100f, w, h))
        assertTrue(FaceClip.isVisible(0f, 0f, 100f, w, h))
        assertTrue(FaceClip.isVisible(1080f, 2400f, 100f, w, h))
    }

    @Test
    fun centreFarOutsideIsClipped() {
        // The band the preview cropped away: the ring would be drawn left of the screen.
        assertFalse(FaceClip.isVisible(-300f, 1200f, 100f, w, h))
        assertFalse(FaceClip.isVisible(1400f, 1200f, 100f, w, h))
        assertFalse(FaceClip.isVisible(540f, -300f, 100f, w, h))
        assertFalse(FaceClip.isVisible(540f, 2700f, 100f, w, h))
    }

    @Test
    fun marginIsAQuarterOfTheRadius() {
        val r = 100f
        val m = r * FaceClip.MARGIN_FRACTION
        assertTrue(FaceClip.isVisible(-m, 1200f, r, w, h))
        assertFalse(FaceClip.isVisible(-m - 1f, 1200f, r, w, h))
        assertTrue(FaceClip.isVisible(w + m, 1200f, r, w, h))
        assertFalse(FaceClip.isVisible(w + m + 1f, 1200f, r, w, h))
        assertTrue(FaceClip.isVisible(540f, h + m, r, w, h))
        assertFalse(FaceClip.isVisible(540f, h + m + 1f, r, w, h))
    }

    @Test
    fun largerRingsGetALargerMargin() {
        assertFalse(FaceClip.isVisible(-40f, 1200f, 100f, w, h))
        assertTrue(FaceClip.isVisible(-40f, 1200f, 200f, w, h))
    }

    @Test
    fun unknownViewSizeKeepsEveryFace() {
        assertTrue(FaceClip.isVisible(-5000f, -5000f, 10f, 0, 0))
        assertTrue(FaceClip.isVisible(-5000f, 10f, 10f, 0, h))
        assertTrue(FaceClip.isVisible(10f, -5000f, 10f, w, 0))
    }
}
