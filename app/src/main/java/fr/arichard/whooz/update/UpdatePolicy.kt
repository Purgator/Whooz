package fr.arichard.whooz.update

/**
 * Every timing decision of the self-updater, with the clock injected so it is testable
 * without Android. [UpdateManager] and [fr.arichard.whooz.ui.UpdateBanner] only translate
 * these answers into I/O.
 */
object UpdatePolicy {

    /** GitHub is asked at most this often; it is unauthenticated and rate limited. */
    const val CHECK_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    /** "Later" silences the banner for this long, then the same version is offered again. */
    const val DISMISS_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    /**
     * Whether the daily check should contact GitHub now.
     *
     * Due when it never ran, when the interval elapsed, or when the clock went backwards
     * (a stale future timestamp would otherwise block checks for days). A deferred update
     * (found earlier on metered data) retries as soon as an unmetered network is available,
     * but not before: on metered data the answer would be "deferred" again.
     */
    fun isCheckDue(nowMs: Long, lastCheckMs: Long, deferred: Boolean, unmetered: Boolean): Boolean {
        if (lastCheckMs <= 0L) return true
        if (lastCheckMs > nowMs) return true
        if (nowMs - lastCheckMs >= CHECK_INTERVAL_MS) return true
        return deferred && unmetered
    }

    /** Downloads cost data: only on unmetered networks, unless the user explicitly asked. */
    fun shouldDownload(unmetered: Boolean, force: Boolean): Boolean = force || unmetered

    /**
     * True while the banner must stay hidden for [version] because the user tapped "Later"
     * on that same version less than [DISMISS_INTERVAL_MS] ago. A different version, an
     * elapsed interval, or a dismissal timestamp in the future (clock moved back) all show
     * the banner again.
     */
    fun isDismissed(version: String, dismissedVersion: String, dismissedAtMs: Long, nowMs: Long): Boolean {
        if (version.isEmpty() || version != dismissedVersion) return false
        val elapsed = nowMs - dismissedAtMs
        return elapsed >= 0L && elapsed < DISMISS_INTERVAL_MS
    }

    /**
     * Whether a finished check updates the throttle timestamp: a check that never reached
     * the network is not a check, so the next launch retries right away.
     */
    fun recordsCheck(reachedServer: Boolean): Boolean = reachedServer
}
