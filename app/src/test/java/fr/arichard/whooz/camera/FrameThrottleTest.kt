package fr.arichard.whooz.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameThrottleTest {
    private val interval = 66_000_000L

    @Test
    fun firstFrameIsAlwaysAccepted() {
        assertTrue(FrameThrottle(interval).accept(123L))
        // Even at a negative or zero clock value.
        assertTrue(FrameThrottle(interval).accept(0L))
        assertTrue(FrameThrottle(interval).accept(-5L))
    }

    @Test
    fun framesInsideTheIntervalAreDropped() {
        val t = FrameThrottle(interval)
        assertTrue(t.accept(1_000L))
        assertFalse(t.accept(1_000L + interval - 1))
        assertTrue(t.accept(1_000L + interval))
    }

    @Test
    fun countsFromTheLastAcceptedFrameNotTheLastOffered() {
        // A 30 fps camera (33 ms) must still get one frame through every 66 ms.
        val t = FrameThrottle(interval)
        var now = 0L
        var accepted = 0
        for (i in 0 until 60) {
            if (t.accept(now)) accepted++
            now += 33_000_000L
        }
        assertEquals(30, accepted)
    }

    @Test
    fun resetAcceptsTheNextFrameAtOnce() {
        val t = FrameThrottle(interval)
        assertTrue(t.accept(1_000L))
        assertFalse(t.accept(1_001L))
        t.reset()
        assertTrue(t.accept(1_002L))
    }

    @Test
    fun clockWrapNeverBlocksForever() {
        // nanoTime may be negative and wrap; a difference computed by subtraction stays sane.
        val t = FrameThrottle(interval)
        assertTrue(t.accept(Long.MAX_VALUE - 10L))
        assertTrue(t.accept(Long.MIN_VALUE + interval))
    }
}
