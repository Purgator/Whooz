package fr.arichard.whooz.core

import fr.arichard.whooz.core.ChooserEngine.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChooserEngineTest {
    private val timings = Timings.finger(StabilizationPreset.NORMAL)

    private fun engine(seed: Int = 1, abortOnLift: Boolean = true) =
        ChooserEngine(timings, Random(seed), abortOnLift)

    private fun slots(vararg s: Int) = intArrayOf(*s)

    private fun runToResult(e: ChooserEngine, startMs: Long): Long {
        var t = startMs
        while (e.phase != Phase.RESULT) {
            t += 16
            e.tick(t)
            assertTrue("round never reached RESULT", t < startMs + 60_000)
        }
        return t
    }

    @Test
    fun startRequiresIdleAndValidSlots() {
        val e = engine()
        assertFalse(e.start(slots(), 0, Mode.Winner(1), 0L))
        assertFalse(e.start(slots(-1, 99), 2, Mode.Winner(1), 0L))
        assertEquals(Phase.IDLE, e.phase)
        assertTrue(e.start(slots(0, 1, 1), 3, Mode.Winner(1), 0L))
        assertEquals(2, e.participantCount)
        assertFalse(e.start(slots(0, 1), 2, Mode.Winner(1), 10L))
    }

    @Test
    fun phaseBoundariesAreExact() {
        val e = engine()
        assertTrue(e.start(slots(0, 1, 2), 3, Mode.Winner(1), 1_000L))
        assertEquals(Phase.COUNTDOWN, e.phase)
        assertEquals(1_000L, e.phaseStartMs)
        e.tick(1_999L)
        assertEquals(Phase.COUNTDOWN, e.phase)
        e.tick(2_000L)
        assertEquals(Phase.ELIMINATING, e.phase)
        assertEquals(2_000L, e.phaseStartMs)
        assertEquals(2, e.eliminationCount)
        // Two losers at 350 ms each; the boundary is chained, not "now".
        e.tick(2_699L)
        assertEquals(Phase.ELIMINATING, e.phase)
        e.tick(2_703L)
        assertEquals(Phase.RESULT, e.phase)
        assertEquals(2_700L, e.phaseStartMs)
        assertEquals(2_700L, e.resultStartMs)
        assertEquals(3L, e.phaseElapsedMs(2_703L))
    }

    @Test
    fun eliminationTimesFollowElimStep() {
        val e = engine()
        e.start(slots(0, 1, 2, 3), 4, Mode.Winner(1), 0L)
        val first = e.eliminationSlotAt(0)
        val second = e.eliminationSlotAt(1)
        val third = e.eliminationSlotAt(2)
        assertEquals(1_000L, e.eliminatedAtMs(first))
        assertEquals(1_350L, e.eliminatedAtMs(second))
        assertEquals(1_700L, e.eliminatedAtMs(third))
        assertEquals(-1L, e.eliminatedAtMs(e.winnerSlot()))
        assertEquals(0, e.eliminationIndex(first))
        assertEquals(-1, e.eliminationIndex(e.winnerSlot()))
        // Alive until the very instant of elimination, whatever the phase.
        assertTrue(e.isAlive(first, 999L))
        assertFalse(e.isAlive(first, 1_000L))
        assertTrue(e.isAlive(second, 1_349L))
        assertFalse(e.isAlive(second, 1_350L))
        assertTrue(e.isAlive(e.winnerSlot(), 99_999L))
        assertFalse(e.isAlive(7, 0L))
    }

    @Test
    fun aLongGapPassesThroughEveryPhase() {
        val e = engine()
        e.start(slots(0, 1, 2), 3, Mode.Winner(1), 0L)
        e.tick(50_000L)
        assertEquals(Phase.RESULT, e.phase)
        assertEquals(1_700L, e.phaseStartMs)
        assertEquals(1, e.roundsCompleted)
    }

    @Test
    fun participantSetIsFrozen() {
        val e = engine()
        e.start(slots(0, 1, 2), 3, Mode.Winner(1), 0L)
        assertTrue(e.isParticipant(0))
        assertTrue(e.isParticipant(2))
        assertFalse(e.isParticipant(3))
        assertFalse(e.isParticipant(-1))
        assertFalse(e.isParticipant(16))
        runToResult(e, 0L)
        assertFalse(e.isParticipant(3))
        assertFalse(e.isWinner(3))
        assertEquals(3, e.participantCount)
    }

    @Test
    fun abortDuringCountdownGivesNoWinner() {
        val e = engine()
        e.start(slots(0, 1, 2), 3, Mode.Winner(1), 0L)
        val transitions = e.transitionCount
        e.abort(500L)
        assertEquals(Phase.IDLE, e.phase)
        assertEquals(500L, e.phaseStartMs)
        assertEquals(transitions + 1, e.transitionCount)
        assertEquals(-1, e.winnerSlot())
        assertEquals(0, e.winnerCount)
        assertNull(e.assignment)
        for (s in 0 until Palette.SIZE) {
            assertFalse(e.isWinner(s))
            assertFalse(e.isParticipant(s))
            assertEquals(-1L, e.eliminatedAtMs(s))
        }
        assertEquals(0, e.roundsCompleted)
        // Abort in IDLE is a no-op, no spurious transition.
        e.abort(600L)
        assertEquals(transitions + 1, e.transitionCount)
    }

    @Test
    fun eliminationOrderIsAPermutationOfLosers() {
        for (seed in 0 until 50) {
            val e = engine(seed)
            e.start(slots(3, 7, 9, 12, 15), 5, Mode.Winner(1), 0L)
            assertEquals(4, e.eliminationCount)
            val seen = BooleanArray(Palette.SIZE)
            for (i in 0 until e.eliminationCount) {
                val s = e.eliminationSlotAt(i)
                assertTrue(e.isParticipant(s))
                assertFalse(e.isWinner(s))
                assertFalse("slot $s eliminated twice", seen[s])
                seen[s] = true
                assertEquals(i, e.eliminationIndex(s))
            }
            assertFalse(seen[e.winnerSlot()])
            assertEquals(1, e.winnerCount)
        }
    }

    @Test
    fun winnerNLeavesExactlyNAlive() {
        val e = engine(3)
        e.start(slots(0, 1, 2, 3, 4), 5, Mode.Winner(2), 0L)
        assertEquals(3, e.eliminationCount)
        assertEquals(2, e.winnerCount)
        val t = runToResult(e, 0L)
        var alive = 0
        for (s in 0 until Palette.SIZE) {
            if (e.isAlive(s, t)) {
                alive++
                assertTrue(e.isWinner(s))
            } else {
                assertFalse(e.isWinner(s))
            }
        }
        assertEquals(2, alive)
    }

    @Test
    fun everyoneWinsWhenParticipantsDoNotExceedN() {
        val e = engine()
        e.start(slots(4, 9), 2, Mode.Winner(3), 0L)
        assertEquals(0, e.eliminationCount)
        assertEquals(2, e.winnerCount)
        assertTrue(e.isWinner(4))
        assertTrue(e.isWinner(9))
        assertEquals(4, e.winnerSlot())
        // ELIMINATING is zero-length: the countdown end lands straight in RESULT.
        e.tick(999L)
        assertEquals(Phase.COUNTDOWN, e.phase)
        e.tick(1_000L)
        assertEquals(Phase.RESULT, e.phase)
        assertEquals(1_000L, e.phaseStartMs)
        assertTrue(e.isAlive(4, 10_000L))
        assertTrue(e.isAlive(9, 10_000L))
    }

    @Test
    fun orderYieldsAPermutation() {
        for (seed in 0 until 20) {
            val e = engine(seed)
            e.start(slots(0, 2, 4, 6, 8, 10), 6, Mode.Order, 0L)
            assertTrue(e.revealing)
            assertEquals(0, e.eliminationCount)
            assertEquals(6, e.winnerCount)
            val seen = BooleanArray(7)
            for (s in 0 until Palette.SIZE) {
                val o = e.orderOf(s)
                if (e.isParticipant(s)) {
                    assertTrue(o in 1..6)
                    assertFalse(seen[o])
                    seen[o] = true
                    assertEquals(1_000L + (o - 1) * ChooserEngine.REVEAL_ORDER_STEP_MS, e.revealAtMs(s))
                } else {
                    assertEquals(-1, o)
                    assertEquals(-1L, e.revealAtMs(s))
                }
                assertEquals(-1, e.teamOf(s))
            }
            // Reveal lasts 150 ms × N.
            e.tick(1_899L)
            assertEquals(Phase.ELIMINATING, e.phase)
            e.tick(1_900L)
            assertEquals(Phase.RESULT, e.phase)
            assertTrue(e.isAlive(0, 5_000L))
        }
    }

    @Test
    fun teamsAreBalancedAndClamped() {
        for (n in 2..9) for (k in 2..3) for (seed in 0 until 5) {
            val e = engine(seed)
            val p = IntArray(n) { it * (16 / n) }
            e.start(p, n, Mode.Teams(k), 0L)
            val expectK = if (k < n) k else n
            assertEquals(expectK, e.teamCount)
            val sizes = IntArray(expectK)
            for (s in 0 until Palette.SIZE) {
                val team = e.teamOf(s)
                if (e.isParticipant(s)) {
                    assertTrue(team in 0 until expectK)
                    sizes[team]++
                    assertEquals(-1, e.orderOf(s))
                    assertTrue(e.revealAtMs(s) >= 1_000L)
                } else {
                    assertEquals(-1, team)
                }
            }
            val min = sizes.min()
            val max = sizes.max()
            assertTrue("sizes $min..$max for n=$n k=$k", max - min <= 1)
            assertEquals(n, sizes.sum())
            assertTrue(e.revealing)
            val reveal = ChooserEngine.REVEAL_TEAM_FADE_MS + ChooserEngine.REVEAL_TEAM_STAGGER_MS * n
            e.tick(1_000L + reveal - 1)
            assertEquals(Phase.ELIMINATING, e.phase)
            e.tick(1_000L + reveal)
            assertEquals(Phase.RESULT, e.phase)
        }
    }

    @Test
    fun twoParticipantsMeanOneEliminationStep() {
        val e = engine()
        e.start(slots(0, 1), 2, Mode.Winner(1), 0L)
        assertEquals(1, e.eliminationCount)
        e.tick(1_349L)
        assertEquals(Phase.ELIMINATING, e.phase)
        e.tick(1_350L)
        assertEquals(Phase.RESULT, e.phase)
        val loser = e.eliminationSlotAt(0)
        assertEquals(1_000L, e.eliminatedAtMs(loser))
        assertTrue(e.winnerSlot() != loser)
    }

    @Test
    fun rearmNeedsAllLiftedAndHoldElapsed() {
        val e = engine()
        e.start(slots(0, 1, 2), 3, Mode.Winner(1), 0L)
        val resultAt = 1_700L
        e.tick(resultAt)
        assertEquals(Phase.RESULT, e.phase)
        assertFalse(e.canRearm(true, resultAt + 2_499L))
        assertFalse(e.canRearm(false, resultAt + 2_500L))
        assertFalse(e.requestRearm(false, resultAt + 2_500L))
        assertEquals(Phase.RESULT, e.phase)
        assertTrue(e.canRearm(true, resultAt + 2_500L))
        assertTrue(e.requestRearm(true, resultAt + 2_600L))
        assertEquals(Phase.FADING, e.phase)
        assertEquals(resultAt + 2_600L, e.phaseStartMs)
        // The winner stays known while fading so the flood can fade from its color.
        assertTrue(e.winnerSlot() >= 0)
        e.tick(resultAt + 2_999L)
        assertEquals(Phase.FADING, e.phase)
        e.tick(resultAt + 3_000L)
        assertEquals(Phase.IDLE, e.phase)
        assertEquals(-1, e.winnerSlot())
        assertEquals(0, e.participantCount)
        assertFalse(e.canRearm(true, 99_999L))
        // And a new round can start.
        assertTrue(e.start(slots(0, 1), 2, Mode.Winner(1), 10_000L))
    }

    @Test
    fun participantLiftedAbortsOnlyWithFlagAndDuringCountdown() {
        val finger = engine(abortOnLift = true)
        finger.start(slots(0, 1, 2), 3, Mode.Winner(1), 0L)
        assertFalse(finger.participantLifted(5, 100L)) // not a participant
        assertEquals(Phase.COUNTDOWN, finger.phase)
        assertTrue(finger.participantLifted(1, 100L))
        assertEquals(Phase.IDLE, finger.phase)

        finger.start(slots(0, 1, 2), 3, Mode.Winner(1), 1_000L)
        finger.tick(2_000L)
        assertEquals(Phase.ELIMINATING, finger.phase)
        assertFalse(finger.participantLifted(0, 2_100L))
        assertEquals(Phase.ELIMINATING, finger.phase)

        val camera = engine(abortOnLift = false)
        camera.start(slots(0, 1, 2), 3, Mode.Winner(1), 0L)
        assertFalse(camera.participantLifted(1, 100L))
        assertEquals(Phase.COUNTDOWN, camera.phase)
        assertTrue(camera.isParticipant(1))
    }

    @Test
    fun outcomeDrawnOncePerRound() {
        val e = engine()
        e.start(slots(0, 1, 2, 3), 4, Mode.Winner(1), 0L)
        val a = e.assignment
        assertNotNull(a)
        val winner = e.winnerSlot()
        val first = e.eliminationSlotAt(0)
        var t = 0L
        while (e.phase != Phase.RESULT) {
            t += 16
            e.tick(t)
            assertSame(a, e.assignment)
            assertEquals(winner, e.winnerSlot())
            assertEquals(first, e.eliminationSlotAt(0))
        }
        assertTrue(a!!.winner[winner])
        assertEquals(3, a.eliminationOrder.size)
        assertEquals(first, a.eliminationOrder[0])
    }

    @Test
    fun transitionCountTracksEveryPhaseChange() {
        val e = engine()
        assertEquals(0, e.transitionCount)
        e.start(slots(0, 1), 2, Mode.Winner(1), 0L)
        assertEquals(1, e.transitionCount)
        e.tick(500L)
        assertEquals(1, e.transitionCount)
        e.tick(1_000L)
        assertEquals(2, e.transitionCount)
        e.tick(1_350L)
        assertEquals(3, e.transitionCount)
        e.requestRearm(true, 10_000L)
        assertEquals(4, e.transitionCount)
        e.tick(10_400L)
        assertEquals(5, e.transitionCount)
        assertEquals(Phase.IDLE, e.phase)
    }

    @Test
    fun roundsCompletedCountsOnlyResults() {
        val e = engine()
        e.start(slots(0, 1), 2, Mode.Winner(1), 0L)
        e.abort(100L)
        assertEquals(0, e.roundsCompleted)
        e.start(slots(0, 1), 2, Mode.Winner(1), 200L)
        e.tick(5_000L)
        assertEquals(1, e.roundsCompleted)
        e.abort(5_100L)
        e.start(slots(0, 1), 2, Mode.Order, 6_000L)
        e.tick(9_000L)
        assertEquals(2, e.roundsCompleted)
    }

    @Test
    fun hapticTicksDueCountsQuarterMarks() {
        val e = engine()
        assertEquals(0, e.hapticTicksDue(0L))
        e.start(slots(0, 1), 2, Mode.Winner(1), 1_000L)
        assertEquals(1, e.hapticTicksDue(1_000L))
        assertEquals(1, e.hapticTicksDue(1_249L))
        assertEquals(2, e.hapticTicksDue(1_250L))
        assertEquals(3, e.hapticTicksDue(1_500L))
        assertEquals(4, e.hapticTicksDue(1_750L))
        assertEquals(4, e.hapticTicksDue(1_999L))
        e.tick(2_000L)
        assertEquals(4, e.hapticTicksDue(2_000L))
        e.abort(2_100L)
        assertEquals(0, e.hapticTicksDue(2_100L))
    }

    @Test
    fun pulsePeriodAcceleratesAcrossCountdown() {
        val e = engine()
        e.start(slots(0, 1), 2, Mode.Winner(1), 0L)
        assertEquals(400L, e.countdownPulsePeriodMs(0L))
        assertEquals(300L, e.countdownPulsePeriodMs(500L))
        assertEquals(200L, e.countdownPulsePeriodMs(1_000L))
        var last = Long.MAX_VALUE
        var t = 0L
        while (t <= 1_000L) {
            val p = e.countdownPulsePeriodMs(t)
            assertTrue(p <= last)
            assertTrue(p in 200L..400L)
            last = p
            t += 16
        }
    }

    @Test
    fun dimTimesSpreadLosersOverTheLastCountdownWindow() {
        val e = engine()
        e.start(slots(0, 1, 2, 3), 4, Mode.Winner(1), 0L)
        val l0 = e.eliminationSlotAt(0)
        val l2 = e.eliminationSlotAt(2)
        assertEquals(600L, e.dimAtMs(l0))
        assertTrue(e.dimAtMs(l2) > e.dimAtMs(l0))
        assertTrue(e.dimAtMs(l2) < 1_000L)
        assertEquals(-1L, e.dimAtMs(e.winnerSlot()))
    }
}
