package fr.arichard.whooz.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceGeometryTest {
    private val out = FloatArray(4)

    private fun convert(l: Float, t: Float, r: Float, b: Float, rot: Int, w: Int = 640, h: Int = 480): FloatArray {
        FaceGeometry.uprightToBuffer(l, t, r, b, rot, w, h, out)
        return out
    }

    private fun assertRect(l: Float, t: Float, r: Float, b: Float, actual: FloatArray) {
        assertEquals("left", l, actual[0], EPS)
        assertEquals("top", t, actual[1], EPS)
        assertEquals("right", r, actual[2], EPS)
        assertEquals("bottom", b, actual[3], EPS)
    }

    @Test
    fun rotation0IsIdentity() {
        assertRect(100f, 50f, 220f, 130f, convert(100f, 50f, 220f, 130f, 0))
    }

    @Test
    fun rotation90HandComputed() {
        // The KDoc example: upright 480×640, a box near the upright left edge ends up near the
        // buffer bottom; x comes from the upright y range, y = 480 - ux.
        assertRect(200f, 280f, 350f, 380f, convert(100f, 200f, 200f, 350f, 90))
    }

    @Test
    fun rotation180HandComputed() {
        // Both axes flip within the same 640×480 frame.
        assertRect(420f, 350f, 540f, 430f, convert(100f, 50f, 220f, 130f, 180))
    }

    @Test
    fun rotation270HandComputed() {
        // Counter-clockwise quarter turn: a box near the upright left edge ends up near the
        // buffer top; y comes from the upright x range, x = 640 - uy.
        assertRect(290f, 100f, 440f, 200f, convert(100f, 200f, 200f, 350f, 270))
    }

    @Test
    fun uprightCornersMapToBufferCorners() {
        // The whole upright image maps back onto the whole buffer for every rotation.
        assertRect(0f, 0f, 640f, 480f, convert(0f, 0f, 640f, 480f, 0))
        assertRect(0f, 0f, 640f, 480f, convert(0f, 0f, 480f, 640f, 90))
        assertRect(0f, 0f, 640f, 480f, convert(0f, 0f, 640f, 480f, 180))
        assertRect(0f, 0f, 640f, 480f, convert(0f, 0f, 480f, 640f, 270))
    }

    @Test
    fun roundTripAgainstReferencePixelRotation() {
        // Independent expectation: paint a rectangle of pixels in the buffer, rotate the pixel
        // grid clockwise with the textbook formula k times, read the painted bounding box in
        // the upright grid and ask uprightToBuffer to bring it back.
        val w = 640
        val h = 480
        val bx0 = 100
        val by0 = 50
        val bx1 = 220
        val by1 = 130
        var img = IntArray(w * h)
        for (y in by0 until by1) for (x in bx0 until bx1) img[y * w + x] = 1
        var curW = w
        var curH = h
        for (k in 0..3) {
            val rotation = k * 90
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = -1
            var maxY = -1
            for (y in 0 until curH) for (x in 0 until curW) {
                if (img[y * curW + x] == 1) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
            // Pixel [minX..maxX] covers the continuous range [minX, maxX + 1).
            FaceGeometry.uprightToBuffer(
                minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat(),
                rotation, w, h, out,
            )
            assertRect(bx0.toFloat(), by0.toFloat(), bx1.toFloat(), by1.toFloat(), out)

            img = rotateClockwise(img, curW, curH)
            val t = curW
            curW = curH
            curH = t
        }
    }

    @Test
    fun outputIsNormalizedAndRotationWraps() {
        // Edges given backwards still come out ordered.
        assertRect(100f, 50f, 220f, 130f, convert(220f, 130f, 100f, 50f, 0))
        assertRect(200f, 280f, 350f, 380f, convert(200f, 350f, 100f, 200f, 90))
        // 450 is a 90, -90 is a 270, 360 is a 0.
        assertRect(200f, 280f, 350f, 380f, convert(100f, 200f, 200f, 350f, 450))
        assertRect(290f, 100f, 440f, 200f, convert(100f, 200f, 200f, 350f, -90))
        assertRect(100f, 50f, 220f, 130f, convert(100f, 50f, 220f, 130f, 360))
        // Anything else never throws and falls back to the identity.
        assertRect(100f, 50f, 220f, 130f, convert(100f, 50f, 220f, 130f, 45))
    }

    @Test
    fun ringFromRectUsesCentreAndLargerSide() {
        val ring = FloatArray(3)
        FaceGeometry.ringFromRect(100f, 200f, 300f, 300f, ring)
        assertEquals(200f, ring[0], EPS)
        assertEquals(250f, ring[1], EPS)
        assertEquals(0.62f * 200f, ring[2], EPS)
        // Taller than wide: the height wins.
        FaceGeometry.ringFromRect(100f, 200f, 150f, 400f, ring)
        assertEquals(125f, ring[0], EPS)
        assertEquals(300f, ring[1], EPS)
        assertEquals(0.62f * 200f, ring[2], EPS)
        // Unordered edges only change nothing but the sign of the sides.
        FaceGeometry.ringFromRect(300f, 300f, 100f, 200f, ring)
        assertEquals(200f, ring[0], EPS)
        assertEquals(250f, ring[1], EPS)
        assertEquals(0.62f * 200f, ring[2], EPS)
    }

    @Test
    fun clampRadius() {
        assertEquals(20f, FaceGeometry.clampRadius(5f, 20f, 300f), EPS)
        assertEquals(300f, FaceGeometry.clampRadius(1000f, 20f, 300f), EPS)
        assertEquals(80f, FaceGeometry.clampRadius(80f, 20f, 300f), EPS)
        assertEquals(20f, FaceGeometry.clampRadius(20f, 20f, 300f), EPS)
        assertEquals(300f, FaceGeometry.clampRadius(300f, 20f, 300f), EPS)
        // Crossed bounds never throw.
        assertTrue(FaceGeometry.clampRadius(50f, 300f, 20f) == 300f)
    }

    /**
     * Textbook clockwise quarter turn of a w × h pixel grid (transpose, then mirror each row):
     * the pixel at (x, y) lands at (h - 1 - y, x) in the h × w result.
     */
    private fun rotateClockwise(src: IntArray, w: Int, h: Int): IntArray {
        val dst = IntArray(w * h)
        val newW = h
        for (y in 0 until h) for (x in 0 until w) {
            val nx = h - 1 - y
            val ny = x
            dst[ny * newW + nx] = src[y * w + x]
        }
        return dst
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
