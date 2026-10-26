package fr.arichard.whooz.camera

import fr.arichard.whooz.core.Palette
import fr.arichard.whooz.core.Timings

/**
 * Turns the jittery per-frame face list of ML Kit into stable players.
 *
 * ML Kit's tracking ids are good but not reliable: a blink, a head turn or a hand in front of
 * the face hands out a new id, and a face missed for a frame or two must not lose its color nor
 * restart the stabilization countdown. The tracker therefore keeps its own identity per player
 * (the slot, which is also the palette color index), matches observations by id first and by
 * geometry second, asks for three consecutive hits before trusting a face, and keeps a confirmed
 * face alive at its last position for [Timings.graceMs] before giving its slot up.
 *
 * Single-threaded by design: [update] runs on the analysis thread and [writeTo] copies the
 * result into a [FaceFrame] right after, so no lock is ever needed. Everything lives in parallel
 * primitive arrays indexed by slot; [update] allocates nothing.
 *
 * All thresholds are reasoned constants gathered here, to tune after the first real session.
 */
class FaceTracker(private val timings: Timings, val capacity: Int = Palette.SIZE) {
    init {
        require(capacity in 1..FaceFrame.CAPACITY) { "capacity must be in 1..${FaceFrame.CAPACITY}" }
    }

    private val state = IntArray(capacity)
    private val mlId = IntArray(capacity)
    private val cx = FloatArray(capacity)
    private val cy = FloatArray(capacity)
    private val radius = FloatArray(capacity)
    private val alpha = FloatArray(capacity)
    private val lastSeenMs = LongArray(capacity)
    private val firstSeenMs = LongArray(capacity)
    /** Time of the first frame without this face, -1 while it is seen. */
    private val missSinceMs = LongArray(capacity)
    private val hitCount = IntArray(capacity)

    // Per-frame scratch, preallocated so matching never allocates.
    private val trackTaken = BooleanArray(capacity)
    private val obsTaken = BooleanArray(FaceObservations.CAPACITY)

    /** Bumped only when the set of CONFIRMED slots changes (confirm or GONE), never on moves. */
    var setVersion: Int = 0
        private set

    /** Number of slots in the CONFIRMED state. */
    var confirmedCount: Int = 0
        private set

    init {
        for (i in 0 until capacity) {
            state[i] = STATE_EMPTY
            missSinceMs[i] = -1L
        }
    }

    /**
     * Feeds one frame of observations taken at [nowMs]. Matching order: identical ML Kit id,
     * then nearest track of similar size within [MATCH_DISTANCE] × radius, then a fresh
     * TENTATIVE track in the lowest free slot. Tracks seen by nobody are then aged: a tentative
     * one is dropped at once, a confirmed one fades for the grace window and then goes GONE.
     */
    fun update(obs: FaceObservations, nowMs: Long) {
        val n = if (obs.count < FaceObservations.CAPACITY) obs.count else FaceObservations.CAPACITY
        for (i in 0 until capacity) trackTaken[i] = false
        for (j in 0 until n) obsTaken[j] = false

        // (1) Same ML Kit id: the detector itself says it is the same face.
        for (j in 0 until n) {
            val id = obs.mlId[j]
            if (id < 0) continue
            for (i in 0 until capacity) {
                if (state[i] != STATE_EMPTY && !trackTaken[i] && mlId[i] == id) {
                    hit(i, j, obs, nowMs)
                    break
                }
            }
        }

        // (2) Geometry: a new id near a track of similar size inherits it (id-swap rescue).
        for (j in 0 until n) {
            if (obsTaken[j]) continue
            val ro = obs.radius[j]
            var best = -1
            var bestD2 = Float.MAX_VALUE
            for (i in 0 until capacity) {
                if (state[i] == STATE_EMPTY || trackTaken[i]) continue
                if (missSinceMs[i] >= 0L && nowMs - missSinceMs[i] >= timings.rescueMs) continue
                val rt = radius[i]
                if (ro < RADIUS_RATIO_MIN * rt || ro > RADIUS_RATIO_MAX * rt) continue
                val limit = MATCH_DISTANCE * (if (rt > ro) rt else ro)
                val dx = obs.cx[j] - cx[i]
                val dy = obs.cy[j] - cy[i]
                val d2 = dx * dx + dy * dy
                if (d2 <= limit * limit && d2 < bestD2) {
                    bestD2 = d2
                    best = i
                }
            }
            if (best >= 0) hit(best, j, obs, nowMs)
        }

        // (3) Whatever is left is a new face: lowest free slot so colors stay compact.
        for (j in 0 until n) {
            if (obsTaken[j]) continue
            val s = lowestFreeSlot()
            if (s < 0) break
            spawn(s, j, obs, nowMs)
        }

        // Age the tracks nobody matched.
        for (i in 0 until capacity) {
            if (state[i] == STATE_EMPTY || trackTaken[i]) continue
            if (state[i] == STATE_TENTATIVE) {
                // Never trusted: one miss is enough to call it noise.
                state[i] = STATE_EMPTY
                continue
            }
            if (missSinceMs[i] < 0L) missSinceMs[i] = nowMs
            val missing = nowMs - missSinceMs[i]
            if (missing >= timings.graceMs) {
                state[i] = STATE_EMPTY
                confirmedCount--
                setVersion++
            } else {
                alpha[i] = graceAlpha(missing)
            }
        }
    }

    /** Copies every live track, in slot order, into [frame]; the frame's image geometry is left alone. */
    fun writeTo(frame: FaceFrame) {
        var n = 0
        for (i in 0 until capacity) {
            if (state[i] == STATE_EMPTY) continue
            frame.slot[n] = i
            frame.cx[n] = cx[i]
            frame.cy[n] = cy[i]
            frame.radius[n] = radius[i]
            frame.confirmed[n] = state[i] == STATE_CONFIRMED
            frame.alpha[n] = alpha[i]
            n++
        }
        frame.count = n
        frame.setVersion = setVersion
        frame.confirmedCount = confirmedCount
    }

    /**
     * Forgets every track (screen paused, round re-armed). Bumps [setVersion] when confirmed
     * players vanish, so a gate watching the version sees the change like any other GONE.
     */
    fun clear() {
        if (confirmedCount > 0) setVersion++
        confirmedCount = 0
        for (i in 0 until capacity) {
            state[i] = STATE_EMPTY
            missSinceMs[i] = -1L
        }
    }

    /** True while [slot] holds a tentative or confirmed face. */
    fun isTracked(slot: Int): Boolean = state[slot] != STATE_EMPTY

    fun isConfirmed(slot: Int): Boolean = state[slot] == STATE_CONFIRMED

    /** True while a confirmed face is in its grace window (ring drawn, fading). */
    fun isMissing(slot: Int): Boolean = state[slot] != STATE_EMPTY && missSinceMs[slot] >= 0L

    fun mlIdOf(slot: Int): Int = mlId[slot]
    fun cxOf(slot: Int): Float = cx[slot]
    fun cyOf(slot: Int): Float = cy[slot]
    fun radiusOf(slot: Int): Float = radius[slot]
    fun alphaOf(slot: Int): Float = alpha[slot]
    fun firstSeenOf(slot: Int): Long = firstSeenMs[slot]
    fun lastSeenOf(slot: Int): Long = lastSeenMs[slot]

    private fun hit(i: Int, j: Int, obs: FaceObservations, nowMs: Long) {
        trackTaken[i] = true
        obsTaken[j] = true
        // A rescued track adopts the new id so the cheap id match works again next frame.
        if (obs.mlId[j] >= 0) mlId[i] = obs.mlId[j]
        cx[i] += (obs.cx[j] - cx[i]) * SMOOTHING
        cy[i] += (obs.cy[j] - cy[i]) * SMOOTHING
        radius[i] += (obs.radius[j] - radius[i]) * SMOOTHING
        lastSeenMs[i] = nowMs
        missSinceMs[i] = -1L
        alpha[i] = 1f
        if (state[i] == STATE_TENTATIVE) {
            hitCount[i]++
            if (hitCount[i] >= CONFIRM_HITS) {
                state[i] = STATE_CONFIRMED
                confirmedCount++
                setVersion++
            }
        }
    }

    private fun spawn(i: Int, j: Int, obs: FaceObservations, nowMs: Long) {
        trackTaken[i] = true
        obsTaken[j] = true
        state[i] = STATE_TENTATIVE
        mlId[i] = obs.mlId[j]
        // First hit is taken as is: smoothing from a stale value would start the ring elsewhere.
        cx[i] = obs.cx[j]
        cy[i] = obs.cy[j]
        radius[i] = obs.radius[j]
        alpha[i] = 1f
        firstSeenMs[i] = nowMs
        lastSeenMs[i] = nowMs
        missSinceMs[i] = -1L
        hitCount[i] = 1
    }

    private fun lowestFreeSlot(): Int {
        for (i in 0 until capacity) if (state[i] == STATE_EMPTY) return i
        return -1
    }

    /** 1.0 at the first missed frame, down to [GRACE_ALPHA_MIN] at the end of the grace window. */
    private fun graceAlpha(missingMs: Long): Float {
        val grace = timings.graceMs
        if (grace <= 0L) return GRACE_ALPHA_MIN
        val t = missingMs.toFloat() / grace.toFloat()
        val a = 1f - (1f - GRACE_ALPHA_MIN) * t
        return if (a < GRACE_ALPHA_MIN) GRACE_ALPHA_MIN else if (a > 1f) 1f else a
    }

    companion object {
        private const val STATE_EMPTY = 0
        private const val STATE_TENTATIVE = 1
        private const val STATE_CONFIRMED = 2

        /** Consecutive detections before a face counts as a player; filters one-frame ghosts. */
        const val CONFIRM_HITS: Int = 3

        /** Exponential smoothing weight of the newest sample: steady ring, ~3 frames of lag. */
        const val SMOOTHING: Float = 0.35f

        /** Max centre distance for a geometric re-attach, as a fraction of the larger radius. */
        const val MATCH_DISTANCE: Float = 0.6f

        /** Accepted observation/track radius ratio for a geometric re-attach. */
        const val RADIUS_RATIO_MIN: Float = 0.6f
        const val RADIUS_RATIO_MAX: Float = 1.6f

        /** Opacity of a missing face at the end of the grace window. */
        const val GRACE_ALPHA_MIN: Float = 0.4f
    }
}
