package fr.arichard.whooz.core

import fr.arichard.whooz.core.ChooserEngine.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Battery rule: no frame is scheduled while nothing moves. */
class AnimationBudgetTest {
    private val timings = Timings.finger(StabilizationPreset.NORMAL)

    @Test
    fun framesOnlyWhileSomethingAnimates() {
        val e = ChooserEngine(timings, Random(1), abortOnLift = true)
        assertFalse(e.needsFrames(0L))

        e.start(intArrayOf(0, 1, 2), 3, Mode.Winner(1), 0L)
        assertTrue(e.needsFrames(0L))
        assertTrue(e.needsFrames(999L))

        e.tick(1_000L)
        assertEquals(Phase.ELIMINATING, e.phase)
        assertTrue(e.needsFrames(1_000L))
        assertTrue(e.needsFrames(1_699L))

        e.tick(1_700L)
        assertEquals(Phase.RESULT, e.phase)
        // Flood growing for floodMs, then a steady result costs nothing.
        assertTrue(e.needsFrames(1_700L))
        assertTrue(e.needsFrames(1_700L + timings.floodMs - 1))
        assertFalse(e.needsFrames(1_700L + timings.floodMs))
        assertFalse(e.needsFrames(60_000L))

        assertTrue(e.requestRearm(true, 10_000L))
        assertEquals(Phase.FADING, e.phase)
        assertTrue(e.needsFrames(10_000L))
        assertTrue(e.needsFrames(10_000L + timings.rearmFadeMs - 1))

        e.tick(10_000L + timings.rearmFadeMs)
        assertEquals(Phase.IDLE, e.phase)
        assertFalse(e.needsFrames(10_400L))
    }

    @Test
    fun abortedRoundStopsFramesImmediately() {
        val e = ChooserEngine(timings, Random(1), abortOnLift = true)
        e.start(intArrayOf(0, 1), 2, Mode.Winner(1), 0L)
        assertTrue(e.needsFrames(500L))
        e.abort(500L)
        assertFalse(e.needsFrames(500L))
    }

    @Test
    fun gateAnimatesOnlyWhileArmingOrCollapsing() {
        val g = StabilityGate(timings.stableMs, timings.collapseMs)
        assertFalse(g.isAnimating(0L))
        g.update(1, 1, 0L)
        assertFalse(g.isAnimating(100L))
        g.update(2, 2, 100L)
        assertTrue(g.isAnimating(100L))
        assertTrue(g.isAnimating(100L + timings.stableMs - 1))
        assertFalse(g.isAnimating(100L + timings.stableMs))
        g.update(1, 2, 2_000L)
        assertTrue(g.isAnimating(2_000L))
        assertFalse(g.isAnimating(2_000L + timings.collapseMs))
    }
}
