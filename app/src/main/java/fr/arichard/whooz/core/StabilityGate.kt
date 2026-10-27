package fr.arichard.whooz.core

/**
 * Decides when the group has held still long enough to start a round: at least two
 * participants and an unchanged set for [stableMs]. Everything is a function of the
 * injected clock, so the view only calls [update] on events and reads the arc value per
 * frame.
 *
 * A set change does not snap the arcs to zero: the last progress animates back down over
 * [collapseMs] (the *collapse*) and the timer only restarts once the collapse is over, so
 * a quick finger shuffle reads as "start again" rather than a glitch.
 *
 * [minCount] is the smallest set worth timing: two for a draw, one for the roll call's lock
 * on a single face.
 */
class StabilityGate(private val stableMs: Long, private val collapseMs: Long, private val minCount: Int = 2) {
    private var count = 0
    private var version = 0
    private var primed = false

    /** When the current progress window started, or -1 while not progressing. */
    private var progressStartMs = -1L

    /** When the current collapse started, or -1 while not collapsing. */
    private var collapseStartMs = -1L
    private var collapseFrom = 0f

    /** Feeds the current participant count and set version; idempotent between changes. */
    fun update(count: Int, setVersion: Int, nowMs: Long) {
        val changed = primed && (setVersion != this.version || (count >= minCount) != (this.count >= minCount))
        if (changed) {
            val from = arc(nowMs)
            if (from > 0f) {
                collapseFrom = from
                collapseStartMs = nowMs
            }
            progressStartMs = -1L
        }
        this.count = count
        this.version = setVersion
        primed = true
        if (count >= minCount && progressStartMs < 0L) {
            // Restart after the collapse so the arc never grows while it is still shrinking.
            progressStartMs = if (isCollapsing(nowMs)) collapseStartMs + collapseMs else nowMs
        }
    }

    /** Arming progress 0..1; rises only while at least [minCount] participants hold still. */
    fun progress(nowMs: Long): Float {
        if (count < minCount || progressStartMs < 0L || nowMs < progressStartMs) return 0f
        return Easing.progress(nowMs - progressStartMs, stableMs)
    }

    /** True once [stableMs] elapsed with an unchanged set of at least [minCount] participants. */
    fun armed(nowMs: Long): Boolean =
        count >= minCount && progressStartMs >= 0L && nowMs - progressStartMs >= stableMs

    /** The arc shrinking back from the last progress to 0 over [collapseMs]; 0 when idle. */
    fun collapseProgress(nowMs: Long): Float {
        if (!isCollapsing(nowMs)) return 0f
        val t = Easing.progress(nowMs - collapseStartMs, collapseMs)
        return collapseFrom * (1f - Easing.easeOutCubic(t))
    }

    fun isCollapsing(nowMs: Long): Boolean =
        collapseStartMs >= 0L && nowMs - collapseStartMs < collapseMs

    /** What the view draws: the collapsing arc while it lasts, the rising progress after. */
    fun arc(nowMs: Long): Float = if (isCollapsing(nowMs)) collapseProgress(nowMs) else progress(nowMs)

    /** True while a frame loop is needed: the arc is collapsing or still rising. */
    fun isAnimating(nowMs: Long): Boolean =
        isCollapsing(nowMs) || (count >= minCount && progressStartMs >= 0L && !armed(nowMs))

    /** Forgets everything; the next [update] starts from zero without a collapse. */
    fun reset() {
        count = 0
        version = 0
        primed = false
        progressStartMs = -1L
        collapseStartMs = -1L
        collapseFrom = 0f
    }
}
