package fr.arichard.whooz.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceFrameTest {
    @Test
    fun copyFromCopiesEveryField() {
        val src = FaceFrame()
        src.count = 2
        src.slot[0] = 3; src.cx[0] = 10f; src.cy[0] = 20f; src.radius[0] = 30f; src.confirmed[0] = true; src.alpha[0] = 0.7f
        src.slot[1] = 5; src.cx[1] = 11f; src.cy[1] = 21f; src.radius[1] = 31f; src.confirmed[1] = false; src.alpha[1] = 1f
        src.setVersion = 9
        src.confirmedCount = 1
        src.bufferW = 640
        src.bufferH = 480
        src.rotation = 90

        val dst = FaceFrame()
        dst.copyFrom(src)
        assertEquals(2, dst.count)
        assertEquals(3, dst.slot[0]); assertEquals(10f, dst.cx[0], 0f); assertEquals(20f, dst.cy[0], 0f)
        assertEquals(30f, dst.radius[0], 0f); assertTrue(dst.confirmed[0]); assertEquals(0.7f, dst.alpha[0], 0f)
        assertEquals(5, dst.slot[1]); assertEquals(11f, dst.cx[1], 0f); assertEquals(21f, dst.cy[1], 0f)
        assertEquals(31f, dst.radius[1], 0f); assertFalse(dst.confirmed[1]); assertEquals(1f, dst.alpha[1], 0f)
        assertEquals(9, dst.setVersion)
        assertEquals(1, dst.confirmedCount)
        assertEquals(640, dst.bufferW)
        assertEquals(480, dst.bufferH)
        assertEquals(90, dst.rotation)

        // The copy is independent of the source.
        src.cx[0] = 99f
        assertEquals(10f, dst.cx[0], 0f)
    }

    @Test
    fun clearKeepsGeometryAndVersion() {
        val f = FaceFrame()
        f.count = 3
        f.confirmedCount = 2
        f.setVersion = 4
        f.bufferW = 640
        f.clear()
        assertEquals(0, f.count)
        assertEquals(0, f.confirmedCount)
        assertEquals(4, f.setVersion)
        assertEquals(640, f.bufferW)
    }

    @Test
    fun capacityMatchesPalette() {
        assertEquals(16, FaceFrame.CAPACITY)
        assertEquals(16, FaceFrame().slot.size)
    }
}
