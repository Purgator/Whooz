package fr.arichard.whooz.camera

/**
 * The rate gate of the analyzer: accepts a frame only when at least [minIntervalNs] passed since
 * the last accepted one. Pure so the "at most ~15 detections per second" rule is unit-tested
 * rather than reasoned about; the caller feeds `System.nanoTime()`.
 *
 * Counts from the last *accepted* frame, not from the last offered one: a camera delivering at
 * 30 fps would otherwise never find a gap of 66 ms and starve the detector.
 */
class FrameThrottle(private val minIntervalNs: Long) {
    private var lastAcceptedNs = 0L
    private var hasAccepted = false

    /** True when the frame offered at [nowNs] may be analysed; the throttle then restarts. */
    fun accept(nowNs: Long): Boolean {
        if (hasAccepted && nowNs - lastAcceptedNs < minIntervalNs) return false
        lastAcceptedNs = nowNs
        hasAccepted = true
        return true
    }

    /** Forgets the last frame so the next offer is accepted at once (re-bind, re-arm). */
    fun reset() {
        hasAccepted = false
    }

    companion object {
        /** 66 ms between detections: ML Kit FAST at 640×480 is close to that on mid phones anyway. */
        const val DEFAULT_INTERVAL_NS: Long = 66_000_000L
    }
}
