package fr.arichard.whooz.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EasingTest {
    private val eps = 1e-5f

    @Test
    fun boundaries() {
        assertEquals(0f, Easing.easeOutCubic(0f), eps)
        assertEquals(1f, Easing.easeOutCubic(1f), eps)
        assertEquals(0f, Easing.easeInQuad(0f), eps)
        assertEquals(1f, Easing.easeInQuad(1f), eps)
        assertEquals(0f, Easing.pop(0f), eps)
        assertEquals(1f, Easing.pop(1f), eps)
        assertEquals(0f, Easing.lerp(0f, 10f, 0f), eps)
        assertEquals(10f, Easing.lerp(0f, 10f, 1f), eps)
        assertEquals(5f, Easing.lerp(0f, 10f, 0.5f), eps)
    }

    @Test
    fun clampKeepsEverythingInRange() {
        assertEquals(0f, Easing.clamp01(-3f), 0f)
        assertEquals(1f, Easing.clamp01(7f), 0f)
        assertEquals(0.25f, Easing.clamp01(0.25f), 0f)
        assertEquals(1f, Easing.easeOutCubic(5f), eps)
        assertEquals(0f, Easing.easeInQuad(-5f), eps)
        assertEquals(10f, Easing.lerp(0f, 10f, 3f), eps)
        assertEquals(0f, Easing.lerp(0f, 10f, -3f), eps)
        assertEquals(0f, Easing.progress(-10L, 100L), 0f)
        assertEquals(1f, Easing.progress(500L, 100L), 0f)
        assertEquals(1f, Easing.progress(0L, 0L), 0f)
        assertEquals(0.5f, Easing.progress(50L, 100L), eps)
    }

    @Test
    fun monotonic() {
        var lastOut = -1f
        var lastIn = -1f
        var t = 0f
        while (t <= 1f) {
            val o = Easing.easeOutCubic(t)
            val i = Easing.easeInQuad(t)
            assertTrue(o >= lastOut)
            assertTrue(i >= lastIn)
            lastOut = o
            lastIn = i
            t += 0.01f
        }
    }

    @Test
    fun popOvershootsThenSettles() {
        var peak = 0f
        var t = 0f
        while (t <= 1f) {
            peak = maxOf(peak, Easing.pop(t))
            t += 0.01f
        }
        assertTrue(peak > 1.05f && peak < 1.15f)
    }

    @Test
    fun pulseWithinRangeAndPeriodic() {
        val period = 400L
        for (ms in 0L..2_000L step 7L) {
            val p = Easing.pulse(ms, period)
            assertTrue(p in 0f..1f)
            assertEquals(p, Easing.pulse(ms + period, period), 1e-4f)
            val b = Easing.breathe(ms, period)
            assertTrue(b in 0f..1f)
        }
        assertEquals(0f, Easing.pulse(0L, period), eps)
        assertEquals(1f, Easing.pulse(period / 2, period), eps)
        assertEquals(0f, Easing.pulse(123L, 0L), 0f)
        assertEquals(0f, Easing.breathe(123L, 0L), 0f)
    }

    @Test
    fun floodCoversTheFarthestCornerAtTheEnd() {
        val w = 1080f
        val h = 2340f
        val diagonal = sqrt(w * w + h * h)
        assertEquals(diagonal, Easing.floodRadius(0f, 0f, w, h, 1f), 1e-2f)
        assertEquals(diagonal, Easing.floodRadius(w, 0f, w, h, 1f), 1e-2f)
        assertEquals(diagonal, Easing.floodRadius(0f, h, w, h, 1f), 1e-2f)
        assertEquals(diagonal, Easing.floodRadius(w, h, w, h, 1f), 1e-2f)
        assertEquals(diagonal / 2f, Easing.floodRadius(w / 2f, h / 2f, w, h, 1f), 1e-2f)
        assertEquals(0f, Easing.floodRadius(300f, 400f, w, h, 0f), 0f)
        val mid = Easing.floodRadius(300f, 400f, w, h, 0.5f)
        assertTrue(mid > 0f && mid < Easing.farthestCorner(300f, 400f, w, h))
    }
}
