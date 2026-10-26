package fr.arichard.whooz.camera

import fr.arichard.whooz.core.Palette

/**
 * One detected face already mapped to VIEW pixels. [mlId] is ML Kit's tracking id, or -1 when
 * tracking gave none; it is only a hint for [FaceTracker], never an identity on its own, because
 * ML Kit reassigns ids after a blink or a head turn.
 *
 * Convenience holder for tests and one-off calls. The per-frame path uses [FaceObservations],
 * which keeps the same fields in parallel arrays so nothing is allocated per face.
 */
data class FaceObservation(val mlId: Int, val cx: Float, val cy: Float, val radius: Float)

/**
 * Reusable buffer of the faces seen in one analysis frame, in VIEW pixels. Parallel primitive
 * arrays with a fixed [CAPACITY]: the analysis thread clears it, fills it and hands it to
 * [FaceTracker.update] every frame without allocating. Faces beyond the capacity are dropped,
 * which cannot matter in practice: the palette has no color for a 17th player anyway.
 */
class FaceObservations {
    /** Number of valid entries in the arrays. */
    var count: Int = 0
        private set

    /** ML Kit tracking id per entry, -1 when unknown. */
    val mlId: IntArray = IntArray(CAPACITY)
    val cx: FloatArray = FloatArray(CAPACITY)
    val cy: FloatArray = FloatArray(CAPACITY)
    val radius: FloatArray = FloatArray(CAPACITY)

    /** Forgets every entry; the arrays keep stale values past [count], which readers must ignore. */
    fun clear() {
        count = 0
    }

    /** Appends one face; returns false (and keeps the buffer unchanged) when it is full. */
    fun add(mlId: Int, cx: Float, cy: Float, radius: Float): Boolean {
        if (count >= CAPACITY) return false
        val i = count
        this.mlId[i] = mlId
        this.cx[i] = cx
        this.cy[i] = cy
        this.radius[i] = radius
        count = i + 1
        return true
    }

    /** Test/one-off convenience; the hot path uses the primitive overload. */
    fun add(obs: FaceObservation): Boolean = add(obs.mlId, obs.cx, obs.cy, obs.radius)

    companion object {
        /** One entry per palette color: more faces than colors could never be drawn. */
        const val CAPACITY: Int = Palette.SIZE
    }
}
