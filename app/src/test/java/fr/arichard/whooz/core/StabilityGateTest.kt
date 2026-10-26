package fr.arichard.whooz.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilityGateTest {
    private val stable = 1500L
    private val collapse = 150L

    @Test
    fun notArmedBelowTwo() {
        val g = StabilityGate(stable, collapse)
        g.update(1, 1, 0L)
        assertEquals(0f, g.progress(5_000L), 0f)
        assertFalse(g.armed(5_000L))
        assertFalse(g.isAnimating(5_000L))
        g.update(0, 2, 6_000L)
        assertFalse(g.armed(20_000L))
    }

    @Test
    fun armedExactlyAtStableMs() {
        val g = StabilityGate(stable, collapse)
        g.update(2, 1, 1_000L)
        assertEquals(0f, g.progress(1_000L), 0f)
        assertEquals(0.5f, g.progress(1_750L), 1e-6f)
        assertFalse(g.armed(2_499L))
        assertTrue(g.isAnimating(2_499L))
        assertTrue(g.armed(2_500L))
        assertEquals(1f, g.progress(2_500L), 0f)
        assertFalse(g.isAnimating(2_500L))
        // Idempotent: feeding the same state again does not restart the clock.
        g.update(2, 1, 2_600L)
        assertTrue(g.armed(2_600L))
    }

    @Test
    fun versionChangeResetsAndCollapses() {
        val g = StabilityGate(stable, collapse)
        g.update(2, 1, 0L)
        assertEquals(0.5f, g.progress(750L), 1e-6f)
        g.update(3, 2, 750L)
        assertEquals(0f, g.progress(750L), 0f)
        assertTrue(g.isCollapsing(750L))
        assertEquals(0.5f, g.collapseProgress(750L), 1e-6f)
        assertEquals(0.5f, g.arc(750L), 1e-6f)
        val mid = g.collapseProgress(800L)
        assertTrue(mid > 0f && mid < 0.5f)
        assertEquals(0f, g.collapseProgress(900L), 0f)
        assertFalse(g.isCollapsing(900L))
        assertTrue(g.isAnimating(800L))
        // The timer restarts after the collapse, not at the change.
        assertEquals(0f, g.progress(900L), 0f)
        assertFalse(g.armed(750L + stable))
        assertTrue(g.armed(900L + stable))
    }

    @Test
    fun dropBelowTwoCollapsesWithoutVersionChange() {
        val g = StabilityGate(stable, collapse)
        g.update(2, 1, 0L)
        g.update(1, 1, 300L)
        assertTrue(g.isCollapsing(300L))
        assertEquals(0f, g.progress(2_000L), 0f)
        assertFalse(g.armed(10_000L))
    }

    @Test
    fun noCollapseWhenNothingToCollapse() {
        val g = StabilityGate(stable, collapse)
        g.update(2, 1, 0L)
        g.update(3, 2, 0L)
        assertFalse(g.isCollapsing(0L))
        assertTrue(g.armed(stable))
    }

    @Test
    fun progressMonotonic() {
        val g = StabilityGate(stable, collapse)
        g.update(2, 1, 0L)
        var last = -1f
        var t = 0L
        while (t <= 2_000L) {
            val p = g.progress(t)
            assertTrue(p >= last)
            assertTrue(p in 0f..1f)
            last = p
            t += 16
        }
    }

    @Test
    fun rearmAfterReset() {
        val g = StabilityGate(stable, collapse)
        g.update(2, 7, 0L)
        assertTrue(g.armed(stable))
        g.reset()
        assertFalse(g.armed(stable))
        assertEquals(0f, g.progress(stable), 0f)
        // Same version as before the reset: no collapse, a fresh window from now.
        g.update(2, 7, 5_000L)
        assertFalse(g.isCollapsing(5_000L))
        assertFalse(g.armed(5_000L + stable - 1))
        assertTrue(g.armed(5_000L + stable))
    }
}
