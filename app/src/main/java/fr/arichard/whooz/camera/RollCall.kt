package fr.arichard.whooz.camera

import fr.arichard.whooz.core.Palette
import fr.arichard.whooz.core.StabilityGate

/**
 * The roll call: players are enrolled one at a time in front of the camera, each lock takes a
 * portrait, and the draw runs over the portraits once everybody is in.
 *
 * Why a third mode: the group camera mode needs every face in the frame at once, which pushes
 * people far from a selfie camera and past the detector's range. Here each player comes close,
 * alone, for a second, so detection is at its best and nobody is ever missed.
 *
 * The lock rule: exactly one confirmed face, unchanged for [lockMs] (a [StabilityGate] with a
 * minimum count of one). Every other situation is a hint, never an action: two faces say "one
 * at a time", and after a lock the next timing only starts for a *new* face: a different ML Kit
 * track id, or any lone face once the frame has been empty for [clearMs]. The same person
 * standing still keeps the same id and is never enrolled twice; a player stepping in as the
 * previous one steps out is a new id and is timed at once, even though the tracker keeps the
 * old face alive for its grace period and may hand its slot to the newcomer.
 *
 * Pure and clock-injected: every rule above is unit-tested. The roster is an ordered list of
 * slots 0..n-1, the slot being the color index of the player's portrait.
 */
class RollCall(lockMs: Long, collapseMs: Long, private val clearMs: Long, val capacity: Int = Palette.SIZE) {

    /** What the screen should say; the view maps these to strings. */
    object Hint {
        /** Nobody enrolled yet: explain the game. */
        const val FIRST = 0
        /** Waiting for a lone face. */
        const val WAITING = 1
        /** A lone face is being timed. */
        const val HOLD = 2
        /** More than one confirmed face. */
        const val ONE_AT_A_TIME = 3
        /** Just locked: the player must leave the frame. */
        const val NEXT = 4
        /** Full roster: nothing more can be added. */
        const val FULL = 5
    }

    private var gate = StabilityGate(lockMs, collapseMs, minCount = 1)

    /** Players enrolled so far; slot i belongs to the i-th enrolled player. */
    var count: Int = 0
        private set

    /** True after a lock until the frame has been empty for [clearMs]. */
    var awaitingClear: Boolean = false
        private set
    private var lockedVersion = -1
    private var emptySinceMs = -1L
    private var confirmedCount = 0

    /** ML Kit id of the last locked face; -1 when it had none. */
    private var lockedMlId = -1

    /**
     * Feeds one face frame. Returns true exactly when a lock fires: the caller then takes the
     * portrait of the (single) confirmed face and calls [enrol]. Nothing locks while the roster
     * is full or while the previous player has not left yet.
     */
    fun update(confirmedCount: Int, setVersion: Int, loneMlId: Int, nowMs: Long): Boolean {
        this.confirmedCount = confirmedCount
        if (awaitingClear) {
            if (confirmedCount == 0) {
                if (emptySinceMs < 0L) emptySinceMs = nowMs
                if (nowMs - emptySinceMs >= clearMs) {
                    awaitingClear = false
                    emptySinceMs = -1L
                }
            } else {
                emptySinceMs = -1L
                // A lone face with a new track id is the next player, provided the tracker
                // also saw the set change (new version): if it's the same version, it's just
                // a tracking id swap for the same person.
                if (confirmedCount == 1 && loneMlId >= 0 && loneMlId != lockedMlId && setVersion != lockedVersion) awaitingClear = false
            }
            if (awaitingClear) {
                gate.update(0, setVersion, nowMs)
                return false
            }
        }
        val lockable = confirmedCount == 1 && count < capacity
        gate.update(if (lockable) 1 else 0, setVersion, nowMs)
        if (lockable && gate.armed(nowMs)) {
            gate.reset()
            awaitingClear = true
            emptySinceMs = -1L
            lockedMlId = loneMlId
            lockedVersion = setVersion
            return true
        }
        return false
    }

    /** Adds the player whose portrait was just taken; returns its slot, or -1 when full. */
    fun enrol(): Int {
        if (count >= capacity) return -1
        return count++
    }

    /** Removes the player at [index] (a tap on the portrait); later players shift down. */
    fun remove(index: Int): Boolean {
        if (index < 0 || index >= count) return false
        count--
        return true
    }

    /** Forgets everybody; the next lock starts a new group. */
    fun clear() {
        count = 0
        awaitingClear = false
        emptySinceMs = -1L
        confirmedCount = 0
        lockedMlId = -1
        lockedVersion = -1
        gate.reset()
    }

    /** New lock timing from the settings; the roster is untouched, the current timer restarts. */
    fun retime(lockMs: Long, collapseMs: Long) {
        gate = StabilityGate(lockMs, collapseMs, minCount = 1)
    }

    /** Lock progress arc 0..1, with the gate's collapse when the face changes. */
    fun arc(nowMs: Long): Float = gate.arc(nowMs)

    /** True while the arc is rising or collapsing: the view needs frames. */
    fun isAnimating(nowMs: Long): Boolean = gate.isAnimating(nowMs)

    /** The draw needs at least two portraits. */
    fun canDraw(): Boolean = count >= 2

    fun isFull(): Boolean = count >= capacity

    fun hint(): Int = when {
        awaitingClear && confirmedCount > 0 -> Hint.NEXT
        isFull() -> Hint.FULL
        confirmedCount >= 2 -> Hint.ONE_AT_A_TIME
        confirmedCount == 1 && !awaitingClear -> Hint.HOLD
        count == 0 -> Hint.FIRST
        else -> Hint.WAITING
    }
}
