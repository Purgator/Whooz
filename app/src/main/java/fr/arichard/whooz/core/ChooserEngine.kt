package fr.arichard.whooz.core

import kotlin.random.Random

/**
 * The round itself: IDLE → COUNTDOWN → ELIMINATING → RESULT → FADING → IDLE, driven purely
 * by the clock the view passes in. The outcome is drawn exactly once, at [start], from the
 * injected [Random]; every later question (who is alive at a given instant, who won, which
 * team, which number) is answered from preallocated per-slot arrays, so the draw loop never
 * allocates and a replayed clock always yields the same picture.
 *
 * There are no listeners: after each [tick] the view compares [transitionCount] with what it
 * saw last and fires haptics or chrome changes itself, which keeps this class free of any
 * threading or lifecycle concern.
 *
 * For [Mode.Order] and [Mode.Teams] nobody is eliminated; the ELIMINATING phase is a reveal
 * instead ([revealing] is true) and lasts [REVEAL_ORDER_STEP_MS] × N or
 * [REVEAL_TEAM_FADE_MS] + [REVEAL_TEAM_STAGGER_MS] × N.
 */
class ChooserEngine(
    val timings: Timings,
    private val random: Random,
    /** Finger mode: a frozen participant lifting during COUNTDOWN aborts the round. */
    val abortOnLift: Boolean,
) {
    enum class Phase { IDLE, COUNTDOWN, ELIMINATING, RESULT, FADING }

    var phase: Phase = Phase.IDLE
        private set

    /** Clock value at which [phase] was entered; exact chained boundaries, never "now". */
    var phaseStartMs: Long = 0L
        private set

    /** Clock value of the last [start]. */
    var roundStartMs: Long = 0L
        private set

    /** Clock value at which RESULT began in the current round; meaningful in RESULT/FADING. */
    var resultStartMs: Long = 0L
        private set

    var mode: Mode = Mode.DEFAULT
        private set

    var participantCount: Int = 0
        private set

    /** True when ELIMINATING is a reveal (Order/Teams) rather than a series of vanishings. */
    var revealing: Boolean = false
        private set

    /** Incremented on every phase change; the view polls it to emit haptics once. */
    var transitionCount: Int = 0
        private set

    /** Rounds that reached RESULT (aborted rounds do not count). */
    var roundsCompleted: Int = 0
        private set

    /** Losers to eliminate this round; 0 when everyone wins or in Order/Teams. */
    var eliminationCount: Int = 0
        private set

    var winnerCount: Int = 0
        private set

    /** Effective number of teams (k clamped to the participant count); 0 outside Teams. */
    var teamCount: Int = 0
        private set

    /** The outcome of the current round, created once per [start]; null in IDLE. */
    var assignment: Assignment? = null
        private set

    private val participant = BooleanArray(Palette.SIZE)
    private val winner = BooleanArray(Palette.SIZE)
    private val order = IntArray(Palette.SIZE)
    private val team = IntArray(Palette.SIZE)
    private val revealIndex = IntArray(Palette.SIZE)
    private val elimination = IntArray(Palette.SIZE)
    private val elimIndex = IntArray(Palette.SIZE)
    private val scratch = IntArray(Palette.SIZE)
    private var eliminatingMs = 0L

    init {
        clearRound()
    }

    /**
     * Freezes the participant set and draws the outcome. Returns false (and does nothing)
     * unless the engine is IDLE and at least one valid slot was given; out-of-range or
     * duplicate slots are ignored.
     */
    fun start(participants: IntArray, count: Int, mode: Mode, nowMs: Long): Boolean {
        if (phase != Phase.IDLE) return false
        clearRound()
        var n = 0
        for (i in 0 until count) {
            val s = participants[i]
            if (s < 0 || s >= Palette.SIZE || participant[s]) continue
            participant[s] = true
            scratch[n] = s
            n++
        }
        if (n == 0) return false
        participantCount = n
        this.mode = mode
        // One uniform permutation decides everything: the tail wins, the head is the
        // elimination order (Winner); positions are play order / team round-robin otherwise.
        shuffle(scratch, n)
        when (mode) {
            is Mode.Winner -> {
                val w = if (mode.count < n) mode.count else n
                val losers = n - w
                for (i in 0 until losers) {
                    elimination[i] = scratch[i]
                    elimIndex[scratch[i]] = i
                }
                for (i in losers until n) winner[scratch[i]] = true
                eliminationCount = losers
                winnerCount = w
                revealing = false
                eliminatingMs = losers * timings.elimStepMs
            }
            Mode.Order -> {
                for (i in 0 until n) {
                    order[scratch[i]] = i + 1
                    revealIndex[scratch[i]] = i
                    winner[scratch[i]] = true
                }
                winnerCount = n
                revealing = true
                eliminatingMs = REVEAL_ORDER_STEP_MS * n
            }
            is Mode.Teams -> {
                val k = if (mode.count < n) mode.count else n
                for (i in 0 until n) {
                    team[scratch[i]] = i % k
                    revealIndex[scratch[i]] = i
                    winner[scratch[i]] = true
                }
                teamCount = k
                winnerCount = n
                revealing = true
                eliminatingMs = REVEAL_TEAM_FADE_MS + REVEAL_TEAM_STAGGER_MS * n
            }
        }
        assignment = Assignment(winner.copyOf(), order.copyOf(), team.copyOf(), elimination.copyOf(eliminationCount))
        roundStartMs = nowMs
        enter(Phase.COUNTDOWN, nowMs)
        return true
    }

    /** Advances through every phase boundary that [nowMs] has passed. */
    fun tick(nowMs: Long) {
        while (true) {
            when (phase) {
                Phase.COUNTDOWN -> {
                    if (nowMs - phaseStartMs < timings.countdownMs) return
                    enter(Phase.ELIMINATING, phaseStartMs + timings.countdownMs)
                }
                Phase.ELIMINATING -> {
                    if (nowMs - phaseStartMs < eliminatingMs) return
                    resultStartMs = phaseStartMs + eliminatingMs
                    roundsCompleted++
                    enter(Phase.RESULT, resultStartMs)
                }
                Phase.FADING -> {
                    if (nowMs - phaseStartMs < timings.rearmFadeMs) return
                    clearRound()
                    enter(Phase.IDLE, phaseStartMs + timings.rearmFadeMs)
                }
                Phase.IDLE, Phase.RESULT -> return
            }
        }
    }

    /** Cancels the round from any phase: no winner, nothing counted. */
    fun abort(nowMs: Long) {
        if (phase == Phase.IDLE) return
        clearRound()
        enter(Phase.IDLE, nowMs)
    }

    /**
     * A frozen participant lifted. Aborts only during COUNTDOWN and only with [abortOnLift]
     * (finger mode); in camera mode a vanished face stays eligible. Returns true if aborted.
     */
    fun participantLifted(slot: Int, nowMs: Long): Boolean {
        if (!abortOnLift || phase != Phase.COUNTDOWN) return false
        if (slot < 0 || slot >= Palette.SIZE || !participant[slot]) return false
        abort(nowMs)
        return true
    }

    /** True when the result may fade out: everyone lifted and the hold time elapsed. */
    fun canRearm(allLifted: Boolean, nowMs: Long): Boolean =
        phase == Phase.RESULT && allLifted && nowMs - phaseStartMs >= timings.resultHoldMs

    /** Starts the fade back to IDLE when [canRearm]; returns whether it did. */
    fun requestRearm(allLifted: Boolean, nowMs: Long): Boolean {
        if (!canRearm(allLifted, nowMs)) return false
        enter(Phase.FADING, nowMs)
        return true
    }

    /**
     * Whether the frame loop must run: every animated phase, plus RESULT only while the
     * flood (or bands) is still growing. A steady result and IDLE cost zero frames.
     *
     * A reveal round (Order/Teams) has no flood: its last number pop or team fade ends at
     * the ELIMINATING boundary by construction (see [start]), so its RESULT is steady from
     * the first frame and asks for none.
     */
    fun needsFrames(nowMs: Long): Boolean = when (phase) {
        Phase.IDLE -> false
        Phase.COUNTDOWN, Phase.ELIMINATING, Phase.FADING -> true
        Phase.RESULT -> !revealing && nowMs - phaseStartMs < timings.floodMs
    }

    fun phaseElapsedMs(nowMs: Long): Long = nowMs - phaseStartMs

    /** Clock value at which ELIMINATING begins (or began) this round. */
    fun eliminationStartMs(): Long = roundStartMs + timings.countdownMs

    fun isParticipant(slot: Int): Boolean = slot >= 0 && slot < Palette.SIZE && participant[slot]

    /** A participant not yet eliminated at [nowMs]; false for non-participants and in IDLE. */
    fun isAlive(slot: Int, nowMs: Long): Boolean {
        if (!isParticipant(slot)) return false
        val at = eliminatedAtMs(slot)
        return at < 0L || nowMs < at
    }

    /** Clock value at which [slot] vanishes (known from COUNTDOWN on), or -1 if it never does. */
    fun eliminatedAtMs(slot: Int): Long {
        if (phase == Phase.IDLE || slot < 0 || slot >= Palette.SIZE || elimIndex[slot] < 0) return -1L
        return eliminationStartMs() + elimIndex[slot] * timings.elimStepMs
    }

    /** Position of [slot] in the elimination sequence (0 = first to go), or -1. */
    fun eliminationIndex(slot: Int): Int =
        if (slot < 0 || slot >= Palette.SIZE) -1 else elimIndex[slot]

    /** Slot eliminated at position [index] of the sequence. */
    fun eliminationSlotAt(index: Int): Int = elimination[index]

    /**
     * Clock value at which a loser starts dimming during the last [COUNTDOWN_DIM_MS] of the
     * countdown, spread evenly in elimination order; -1 for survivors.
     */
    fun dimAtMs(slot: Int): Long {
        val i = eliminationIndex(slot)
        if (phase == Phase.IDLE || i < 0) return -1L
        return eliminationStartMs() - COUNTDOWN_DIM_MS + i * COUNTDOWN_DIM_MS / eliminationCount
    }

    /** Clock value at which a number pops or a team color fades in (Order/Teams), or -1. */
    fun revealAtMs(slot: Int): Long {
        if (!revealing || !isParticipant(slot)) return -1L
        val step = if (mode is Mode.Order) REVEAL_ORDER_STEP_MS else REVEAL_TEAM_STAGGER_MS
        return eliminationStartMs() + revealIndex[slot] * step
    }

    fun isWinner(slot: Int): Boolean = slot >= 0 && slot < Palette.SIZE && winner[slot]

    /** Team 0..k-1 of [slot] (Teams), else -1. */
    fun teamOf(slot: Int): Int = if (slot < 0 || slot >= Palette.SIZE) -1 else team[slot]

    /** Play order 1..N of [slot] (Order), else -1. */
    fun orderOf(slot: Int): Int = if (slot < 0 || slot >= Palette.SIZE) -1 else order[slot]

    /** Lowest winning slot, or -1 in IDLE; in Order/Teams every participant "wins". */
    fun winnerSlot(): Int {
        for (s in 0 until Palette.SIZE) if (winner[s]) return s
        return -1
    }

    /** Pulse period shrinking from [PULSE_PERIOD_START_MS] to [PULSE_PERIOD_END_MS] across the countdown. */
    fun countdownPulsePeriodMs(nowMs: Long): Long {
        if (phase != Phase.COUNTDOWN) return PULSE_PERIOD_END_MS
        val t = Easing.progress(nowMs - phaseStartMs, timings.countdownMs)
        return Easing.lerp(PULSE_PERIOD_START_MS.toFloat(), PULSE_PERIOD_END_MS.toFloat(), t).toLong()
    }

    /**
     * How many of the [COUNTDOWN_TICKS] evenly spaced countdown ticks (0, ¼, ½, ¾) have
     * passed, so the view can emit the ones it missed between frames; 0 in IDLE, all of
     * them once the countdown is over.
     */
    fun hapticTicksDue(nowMs: Long): Int {
        if (phase == Phase.IDLE) return 0
        if (phase != Phase.COUNTDOWN) return COUNTDOWN_TICKS
        val elapsed = nowMs - phaseStartMs
        if (elapsed < 0L) return 0
        val due = (elapsed * COUNTDOWN_TICKS / timings.countdownMs + 1).toInt()
        return if (due > COUNTDOWN_TICKS) COUNTDOWN_TICKS else due
    }

    private fun enter(next: Phase, atMs: Long) {
        phase = next
        phaseStartMs = atMs
        transitionCount++
    }

    private fun clearRound() {
        for (s in 0 until Palette.SIZE) {
            participant[s] = false
            winner[s] = false
            order[s] = -1
            team[s] = -1
            revealIndex[s] = -1
            elimIndex[s] = -1
            elimination[s] = -1
        }
        participantCount = 0
        eliminationCount = 0
        winnerCount = 0
        teamCount = 0
        revealing = false
        eliminatingMs = 0L
        assignment = null
    }

    /** Fisher–Yates on the first [n] entries; uniform as long as [random] is. */
    private fun shuffle(a: IntArray, n: Int) {
        var i = n - 1
        while (i > 0) {
            val j = random.nextInt(i + 1)
            val t = a[i]
            a[i] = a[j]
            a[j] = t
            i--
        }
    }

    companion object {
        /** Numbers pop in one every 150 ms in Order mode. */
        const val REVEAL_ORDER_STEP_MS: Long = 150
        /** Each ring crossfades to its team color over 300 ms... */
        const val REVEAL_TEAM_FADE_MS: Long = 300
        /** ...starting 100 ms after the previous one. */
        const val REVEAL_TEAM_STAGGER_MS: Long = 100
        /** Losers dim one by one during the last 400 ms of the countdown. */
        const val COUNTDOWN_DIM_MS: Long = 400
        const val PULSE_PERIOD_START_MS: Long = 400
        const val PULSE_PERIOD_END_MS: Long = 200
        const val COUNTDOWN_TICKS: Int = 4
    }
}
