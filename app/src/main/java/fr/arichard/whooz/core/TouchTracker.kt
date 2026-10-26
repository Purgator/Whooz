package fr.arichard.whooz.core

import kotlin.math.abs

/**
 * Pointer bookkeeping for the finger arena, kept in parallel primitive arrays so the touch
 * and draw paths never allocate. Each tracked pointer owns a *slot*: the lowest free color
 * index at the time it landed, kept for its whole life so a finger never changes color
 * because a neighbour lifted.
 *
 * Pointers can be *absorbed*: tracked and drawn as dim dots but excluded from the
 * participant set (late fingers during a round, bystanders in RESULT). Absorbed pointers
 * never touch [setVersion], which is what the [StabilityGate] watches, so a stray finger
 * cannot restart the arming.
 */
class TouchTracker(val capacity: Int = Palette.SIZE) {
    private val ids = IntArray(capacity)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val slots = IntArray(capacity)
    private val absorbed = BooleanArray(capacity)
    private val slotUsed = BooleanArray(capacity)

    /** Tracked pointers, absorbed ones included; entries 0 until count are valid. */
    var count: Int = 0
        private set

    /** Tracked pointers that take part in the draw (not absorbed). */
    var participantCount: Int = 0
        private set

    /** Bumped only when the set of participating pointers changes, never on moves. */
    var setVersion: Int = 0
        private set

    /** Whether the last [up] came from a cancelled pointer (palm rejection, gesture nav). */
    var lastUpCancelled: Boolean = false
        private set

    /**
     * Registers a pointer. Returns false when it is ignored: a non-finger tool, a full
     * tracker (17th pointer) or an id already down (treated as a move so a duplicate
     * ACTION_DOWN can never leak a second circle).
     */
    fun down(id: Int, x: Float, y: Float, toolType: Int, absorbed: Boolean = false): Boolean {
        if (toolType != TOOL_TYPE_UNKNOWN && toolType != TOOL_TYPE_FINGER) return false
        val existing = indexOfId(id)
        if (existing >= 0) {
            xs[existing] = x
            ys[existing] = y
            return false
        }
        if (count >= capacity) return false
        val slot = lowestFreeSlot()
        val i = count
        ids[i] = id
        xs[i] = x
        ys[i] = y
        slots[i] = slot
        this.absorbed[i] = absorbed
        slotUsed[slot] = true
        count = i + 1
        if (!absorbed) {
            participantCount++
            setVersion++
        }
        return true
    }

    /** Updates a pointer position; true when it moved by at least one pixel on an axis. */
    fun move(id: Int, x: Float, y: Float): Boolean {
        val i = indexOfId(id)
        if (i < 0) return false
        val moved = abs(x - xs[i]) >= 1f || abs(y - ys[i]) >= 1f
        xs[i] = x
        ys[i] = y
        return moved
    }

    /** Removes a pointer; returns the slot it freed, or -1 for an unknown id. */
    fun up(id: Int): Int = up(id, false)

    /**
     * Removes a pointer, remembering whether the lift was a cancellation so the view can
     * skip the haptic. Returns the freed slot or -1.
     */
    fun up(id: Int, cancelled: Boolean): Int {
        val i = indexOfId(id)
        if (i < 0) return -1
        lastUpCancelled = cancelled
        val slot = slots[i]
        val wasParticipant = !absorbed[i]
        slotUsed[slot] = false
        // Shift the tail down to keep insertion order: drawing stays stable and the
        // array never needs a "used" mask of its own.
        val last = count - 1
        var j = i
        while (j < last) {
            ids[j] = ids[j + 1]
            xs[j] = xs[j + 1]
            ys[j] = ys[j + 1]
            slots[j] = slots[j + 1]
            absorbed[j] = absorbed[j + 1]
            j++
        }
        count = last
        if (wasParticipant) {
            participantCount--
            setVersion++
        }
        return slot
    }

    /** Forgets every pointer: ids are only unique within one gesture. */
    fun clear() {
        val hadParticipants = participantCount > 0
        for (s in 0 until capacity) slotUsed[s] = false
        count = 0
        participantCount = 0
        lastUpCancelled = false
        if (hadParticipants) setVersion++
    }

    /** Index of the pointer with [id], or -1. */
    fun indexOfId(id: Int): Int {
        for (i in 0 until count) if (ids[i] == id) return i
        return -1
    }

    fun idAt(index: Int): Int = ids[index]
    fun xAt(index: Int): Float = xs[index]
    fun yAt(index: Int): Float = ys[index]
    fun slotAt(index: Int): Int = slots[index]
    fun isAbsorbed(index: Int): Boolean = absorbed[index]

    /** Index of the pointer owning [slot], or -1 when that slot is free. */
    fun indexOfSlot(slot: Int): Int {
        for (i in 0 until count) if (slots[i] == slot) return i
        return -1
    }

    /** Writes the participating slots (insertion order) into [out]; returns how many. */
    fun participantsSnapshot(out: IntArray): Int {
        var n = 0
        for (i in 0 until count) {
            if (absorbed[i]) continue
            if (n >= out.size) break
            out[n] = slots[i]
            n++
        }
        return n
    }

    private fun lowestFreeSlot(): Int {
        for (s in 0 until capacity) if (!slotUsed[s]) return s
        return -1 // unreachable: count < capacity guarantees a free slot
    }

    companion object {
        const val MAX_POINTERS: Int = Palette.SIZE

        // Mirrors android.view.MotionEvent.TOOL_TYPE_* so this class stays Android-free.
        const val TOOL_TYPE_UNKNOWN: Int = 0
        const val TOOL_TYPE_FINGER: Int = 1
        const val TOOL_TYPE_STYLUS: Int = 2
        const val TOOL_TYPE_MOUSE: Int = 3
        const val TOOL_TYPE_ERASER: Int = 4
    }
}
