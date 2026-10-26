package fr.arichard.whooz.finger

import fr.arichard.whooz.core.ChooserEngine.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

class HintResolverTest {

    @Test
    fun idleCountsFingers() {
        assertEquals(HintResolver.IDLE, HintResolver.resolve(Phase.IDLE, 0, tooMany = false, holdElapsed = false))
        assertEquals(HintResolver.ONE_MORE, HintResolver.resolve(Phase.IDLE, 1, tooMany = false, holdElapsed = false))
        assertEquals(HintResolver.ARMING, HintResolver.resolve(Phase.IDLE, 2, tooMany = false, holdElapsed = false))
        assertEquals(HintResolver.ARMING, HintResolver.resolve(Phase.IDLE, 9, tooMany = false, holdElapsed = false))
    }

    @Test
    fun roundPhasesShowNothing() {
        for (phase in arrayOf(Phase.COUNTDOWN, Phase.ELIMINATING, Phase.FADING)) {
            assertEquals(HintResolver.ROUND, HintResolver.resolve(phase, 3, tooMany = false, holdElapsed = false))
            assertEquals(HintResolver.ROUND, HintResolver.resolve(phase, 3, tooMany = false, holdElapsed = true))
        }
    }

    @Test
    fun resultHintsOnlyAfterTheHold() {
        assertEquals(HintResolver.ROUND, HintResolver.resolve(Phase.RESULT, 3, tooMany = false, holdElapsed = false))
        assertEquals(HintResolver.RESULT_HOLD, HintResolver.resolve(Phase.RESULT, 3, tooMany = false, holdElapsed = true))
        assertEquals(HintResolver.RESULT_HOLD, HintResolver.resolve(Phase.RESULT, 0, tooMany = false, holdElapsed = true))
    }

    @Test
    fun tooManyWinsEverywhere() {
        assertEquals(HintResolver.TOO_MANY, HintResolver.resolve(Phase.IDLE, 16, tooMany = true, holdElapsed = false))
        assertEquals(HintResolver.TOO_MANY, HintResolver.resolve(Phase.COUNTDOWN, 16, tooMany = true, holdElapsed = false))
        assertEquals(HintResolver.TOO_MANY, HintResolver.resolve(Phase.RESULT, 16, tooMany = true, holdElapsed = true))
    }
}
