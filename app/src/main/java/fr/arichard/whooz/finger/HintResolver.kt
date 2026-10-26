package fr.arichard.whooz.finger

import fr.arichard.whooz.core.ChooserEngine.Phase

/**
 * Which hint the finger screen shows for a given arena state. Pure so the mapping can be
 * unit-tested without a view: the arena computes it after every touch and frame and only
 * tells the activity when the answer changes.
 *
 * Priority: a digitizer overflow beats everything (the players must know why a finger has
 * no circle), then the engine phase, then the finger count while gathering.
 */
object HintResolver {
    /** "Everyone, put one finger on the screen". */
    const val IDLE = 0
    /** Exactly one finger down: "Need one more finger". */
    const val ONE_MORE = 1
    /** Two or more fingers, the gate is filling: "Hold still…". */
    const val ARMING = 2
    /** Countdown, elimination, early result, fade: no hint at all. */
    const val ROUND = 3
    /** Result held long enough: "Lift to play again". */
    const val RESULT_HOLD = 4
    /** More pointers than the arena can track: "Too many fingers". */
    const val TOO_MANY = 5

    fun resolve(phase: Phase, participantCount: Int, tooMany: Boolean, holdElapsed: Boolean): Int {
        if (tooMany) return TOO_MANY
        return when (phase) {
            Phase.IDLE -> when {
                participantCount <= 0 -> IDLE
                participantCount == 1 -> ONE_MORE
                else -> ARMING
            }
            Phase.COUNTDOWN, Phase.ELIMINATING, Phase.FADING -> ROUND
            Phase.RESULT -> if (holdElapsed) RESULT_HOLD else ROUND
        }
    }
}
