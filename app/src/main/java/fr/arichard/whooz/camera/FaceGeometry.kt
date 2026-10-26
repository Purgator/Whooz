package fr.arichard.whooz.camera

/**
 * Pure geometry of the face pipeline: undoing ML Kit's rotation and turning a box into a ring.
 * Isolated here so the only part of the transform chain that cannot be seen without a device
 * is covered by hand-computed unit tests for all four rotations.
 */
object FaceGeometry {
    /** Ring radius relative to the larger side of the face box: slightly beyond the face. */
    const val RING_RADIUS_FACTOR: Float = 0.62f

    /**
     * Converts a bounding box from UPRIGHT image coordinates back to raw buffer coordinates.
     *
     * Rotation convention: [rotationDegrees] is ML Kit's `InputImage` rotation, i.e. the
     * CLOCKWISE rotation that must be applied to the raw buffer (bufferW × bufferH) to make it
     * upright. ML Kit reports faces in the upright image, whose size is bufferH × bufferW for
     * 90 and 270 and bufferW × bufferH for 0 and 180. The CameraX matrices, however, speak
     * buffer coordinates, so the box has to be rotated back before any matrix mapping.
     *
     * Forward mapping of a buffer point (bx, by) to an upright point (ux, uy), with W = bufferW
     * and H = bufferH:
     * - 0:   ux = bx,     uy = by
     * - 90:  ux = H − by, uy = bx       (the buffer's bottom edge becomes the upright left edge)
     * - 180: ux = W − bx, uy = H − by
     * - 270: ux = by,     uy = W − bx   (the buffer's top edge becomes the upright left edge)
     *
     * This method applies the inverse:
     * - 90:  bx = uy,     by = H − ux
     * - 180: bx = W − ux, by = H − uy
     * - 270: bx = W − uy, by = ux
     *
     * Worked example, the usual selfie case: a 640 × 480 landscape buffer with rotation 90
     * gives a 480 × 640 portrait upright image. A face box at upright (left 100, top 200,
     * right 200, bottom 350), i.e. near the upright left edge, lands in the buffer at
     * (left 200, top 280, right 350, bottom 380): x comes from the upright y range (200..350),
     * and y = 480 − ux puts it near the buffer bottom (480 − 200 = 280 .. 480 − 100 = 380).
     *
     * Any rotation that is not a multiple of 90 is treated as 0; ML Kit never hands one out.
     * Writes left, top, right, bottom into [out], normalized so left ≤ right and top ≤ bottom.
     */
    fun uprightToBuffer(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotationDegrees: Int,
        bufferW: Int,
        bufferH: Int,
        out: FloatArray,
    ) {
        val w = bufferW.toFloat()
        val h = bufferH.toFloat()
        val x0: Float
        val y0: Float
        val x1: Float
        val y1: Float
        when (normalizeRotation(rotationDegrees)) {
            90 -> {
                x0 = top; y0 = h - left
                x1 = bottom; y1 = h - right
            }
            180 -> {
                x0 = w - left; y0 = h - top
                x1 = w - right; y1 = h - bottom
            }
            270 -> {
                x0 = w - top; y0 = left
                x1 = w - bottom; y1 = right
            }
            else -> {
                x0 = left; y0 = top
                x1 = right; y1 = bottom
            }
        }
        out[0] = if (x0 < x1) x0 else x1
        out[1] = if (y0 < y1) y0 else y1
        out[2] = if (x0 < x1) x1 else x0
        out[3] = if (y0 < y1) y1 else y0
    }

    /**
     * Turns a face box into the ring drawn around it: centre of the box, radius
     * [RING_RADIUS_FACTOR] × the larger side, so a turned head (narrow box) keeps a steady ring.
     * Writes cx, cy, radius into [out]. Accepts unordered edges: only sizes matter.
     */
    fun ringFromRect(left: Float, top: Float, right: Float, bottom: Float, out: FloatArray) {
        val w = if (right >= left) right - left else left - right
        val h = if (bottom >= top) bottom - top else top - bottom
        out[0] = (left + right) * 0.5f
        out[1] = (top + bottom) * 0.5f
        out[2] = RING_RADIUS_FACTOR * (if (w > h) w else h)
    }

    /** Keeps a ring radius within drawable bounds; total, [min] wins when the bounds cross. */
    fun clampRadius(r: Float, min: Float, max: Float): Float =
        if (r < min) min else if (r > max) max else r

    /** Folds any angle onto 0..359 so negative or wrapped rotations still match the four cases. */
    private fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
