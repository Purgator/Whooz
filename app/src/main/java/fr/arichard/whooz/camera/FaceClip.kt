package fr.arichard.whooz.camera

/**
 * Decides whether a face mapped to VIEW pixels is actually on screen.
 *
 * The PreviewView is FILL_CENTER on a tall phone while the analysis image is 4:3, so a band on
 * both sides of the sensor is cropped out of the preview, yet ML Kit still sees it: a bystander
 * behind the players, or someone at the edge of the group, maps to a ring centre left of 0 or
 * right of the width. Such a face is invisible, so it must neither arm the gate nor take part
 * in a round, let alone win with its ring drawn off-screen. Pure so the rule is unit-tested
 * instead of reasoned about on the one test phone.
 *
 * The margin is a fraction of the ring radius: a face whose centre just crossed the edge still
 * shows most of its ring and keeps counting, and the tracker's grace period absorbs the flicker
 * of a face straddling the edge. An unknown view size (before layout) keeps every face: frames
 * cannot be mapped before layout anyway, and dropping them silently would hide a bug.
 */
object FaceClip {
    /** How far past the edge, in ring radii, a centre may be and still count as visible. */
    const val MARGIN_FRACTION = 0.25f

    /** True when ([cx], [cy]) lies inside the [width]×[height] view grown by the margin. */
    fun isVisible(cx: Float, cy: Float, radius: Float, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        val m = radius * MARGIN_FRACTION
        return cx >= -m && cx <= width + m && cy >= -m && cy <= height + m
    }
}
