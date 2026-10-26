package fr.arichard.whooz.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The whole point of the app: every finger must have the same chance. */
class UniformityTest {
    @Test
    fun fourParticipantsEachWinAQuarter() {
        val rounds = 10_000
        val engine = ChooserEngine(Timings.finger(StabilizationPreset.FAST), Random(20261026), abortOnLift = true)
        val wins = IntArray(Palette.SIZE)
        val participants = intArrayOf(0, 1, 2, 3)
        var now = 0L
        for (r in 0 until rounds) {
            assertTrue(engine.start(participants, 4, Mode.Winner(1), now))
            now += 10_000L
            engine.tick(now)
            assertEquals(ChooserEngine.Phase.RESULT, engine.phase)
            wins[engine.winnerSlot()]++
            engine.abort(now)
        }
        for (s in 0 until 4) {
            val share = wins[s].toDouble() / rounds
            assertTrue("slot $s won ${wins[s]} / $rounds", share > 0.23 && share < 0.27)
        }
        assertEquals(rounds, wins.sum())
    }

    @Test
    fun eliminationPositionsAreUniformToo() {
        // A loser must not be predictably first out: each slot goes first in ~1/3 of rounds.
        val rounds = 6_000
        val engine = ChooserEngine(Timings.finger(StabilizationPreset.FAST), Random(7), abortOnLift = true)
        val firstOut = IntArray(Palette.SIZE)
        val participants = intArrayOf(5, 6, 7)
        for (r in 0 until rounds) {
            engine.start(participants, 3, Mode.Winner(1), 0L)
            firstOut[engine.eliminationSlotAt(0)]++
            engine.abort(1L)
        }
        for (s in 5..7) {
            val share = firstOut[s].toDouble() / rounds
            assertTrue("slot $s first out ${firstOut[s]} / $rounds", share > 0.30 && share < 0.37)
        }
    }
}
