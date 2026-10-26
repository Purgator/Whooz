package fr.arichard.whooz.camera

/**
 * The "nobody is in front of the camera" clock: once no confirmed face, tap or round has been
 * seen for [timeoutMs], the camera should be released, exactly like a result left untouched.
 * Without it an idle camera screen streams, detects and redraws for hours.
 *
 * Designed as a lazy timer so the 15 Hz frame path stays free of Handler traffic: the owner
 * posts one check for [delayMs] and the check re-posts itself for the remainder when activity
 * happened meanwhile. Feeding [activity] per frame is one assignment. Pure and clock-injected so
 * the three-minute rule is tested, not eyeballed.
 */
class IdleSleep(private val timeoutMs: Long) {
    private var lastActivityMs = 0L

    /** Something worth staying awake for happened at [nowMs]. */
    fun activity(nowMs: Long) {
        lastActivityMs = nowMs
    }

    /** 0 when the camera should sleep now, otherwise how long to wait before asking again. */
    fun delayMs(nowMs: Long): Long {
        val remaining = timeoutMs - (nowMs - lastActivityMs)
        return if (remaining > 0L) remaining else 0L
    }

    /** True once [timeoutMs] elapsed since the last [activity]. */
    fun shouldSleep(nowMs: Long): Boolean = delayMs(nowMs) == 0L
}
