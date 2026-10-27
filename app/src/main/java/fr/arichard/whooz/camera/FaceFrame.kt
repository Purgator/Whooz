package fr.arichard.whooz.camera

import fr.arichard.whooz.core.Palette

/**
 * Preallocated snapshot of the tracked faces, in VIEW pixels, as the overlay draws them.
 *
 * Two instances are double-buffered between the analysis thread (which fills one through
 * [FaceTracker.writeTo]) and the UI thread (which draws the other), so the draw path never waits
 * on detection and never allocates. Every field is a plain var or a fixed array on purpose: the
 * frame is a struct copied with [copyFrom], not an object with behaviour. Entries are sorted by
 * [slot], and the slot is the palette color index of the player.
 *
 * [bufferW], [bufferH] and [rotation] describe the analysis image the faces came from; the
 * tracker leaves them alone, the camera glue fills them so the debug overlay can draw the
 * mapped analysis bounds and prove the transform chain on the first real device.
 */
class FaceFrame {
    /** Number of valid entries in the arrays below. */
    var count: Int = 0

    /** Player slot (= color index) of each entry, strictly increasing. */
    val slot: IntArray = IntArray(CAPACITY)
    val cx: FloatArray = FloatArray(CAPACITY)
    val cy: FloatArray = FloatArray(CAPACITY)
    val radius: FloatArray = FloatArray(CAPACITY)

    /** True once the tracker trusts the face; tentative faces are drawn thin and dim. */
    val confirmed: BooleanArray = BooleanArray(CAPACITY)

    /** Ring opacity 0.4..1: fades while a confirmed face is missing during the grace window. */
    val alpha: FloatArray = FloatArray(CAPACITY)

    /** ML Kit tracking id of each face, -1 when none; a new id means a new continuous track. */
    val mlId: IntArray = IntArray(CAPACITY)

    /** Bumped only when the set of confirmed slots changed; drives the stability gate. */
    var setVersion: Int = 0
    var confirmedCount: Int = 0

    /** Analysis image geometry for the debug overlay (see class doc). */
    var bufferW: Int = 0
    var bufferH: Int = 0
    var rotation: Int = 0

    /** Full copy of [other] into this frame; no allocation, arrays are overwritten in place. */
    fun copyFrom(other: FaceFrame) {
        val n = other.count
        for (i in 0 until n) {
            slot[i] = other.slot[i]
            cx[i] = other.cx[i]
            cy[i] = other.cy[i]
            radius[i] = other.radius[i]
            confirmed[i] = other.confirmed[i]
            alpha[i] = other.alpha[i]
            mlId[i] = other.mlId[i]
        }
        count = n
        setVersion = other.setVersion
        confirmedCount = other.confirmedCount
        bufferW = other.bufferW
        bufferH = other.bufferH
        rotation = other.rotation
    }

    /** Empties the face list; the geometry and version fields are kept. */
    fun clear() {
        count = 0
        confirmedCount = 0
    }

    companion object {
        /** One entry per palette color. */
        const val CAPACITY: Int = Palette.SIZE
    }
}
