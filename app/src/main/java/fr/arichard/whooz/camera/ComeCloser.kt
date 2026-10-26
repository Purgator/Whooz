package fr.arichard.whooz.camera

/**
 * Decides when to show "Come closer": confirmed faces exist, yet every confirmed ring has stayed
 * below [minRadiusPx] for [holdMs]. Pure and clock-injected so the 3-second rule is tested once
 * instead of eyeballed on a device.
 *
 * Tentative faces are ignored on both sides: a far face that is not yet trusted must not start
 * the timer, and a large tentative face must not silence it. Any confirmed face large enough, or
 * no confirmed face at all, resets the timer so the hint never lingers after the group moved.
 */
class ComeCloser(private val minRadiusPx: Float, private val holdMs: Long) {
    private var smallSinceMs = -1L

    /** Feeds the latest frame; returns true while the hint should be shown. */
    fun update(frame: FaceFrame, nowMs: Long): Boolean {
        var confirmed = 0
        var large = false
        for (i in 0 until frame.count) {
            if (!frame.confirmed[i]) continue
            confirmed++
            if (frame.radius[i] >= minRadiusPx) {
                large = true
                break
            }
        }
        if (confirmed == 0 || large) {
            smallSinceMs = -1L
            return false
        }
        if (smallSinceMs < 0L) smallSinceMs = nowMs
        return nowMs - smallSinceMs >= holdMs
    }

    fun reset() {
        smallSinceMs = -1L
    }
}
