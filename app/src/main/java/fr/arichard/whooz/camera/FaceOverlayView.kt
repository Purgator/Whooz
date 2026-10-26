package fr.arichard.whooz.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import fr.arichard.whooz.core.ChooserEngine
import fr.arichard.whooz.core.Easing
import fr.arichard.whooz.core.Mode
import fr.arichard.whooz.core.Palette
import fr.arichard.whooz.core.StabilityGate
import fr.arichard.whooz.core.StabilizationPreset
import fr.arichard.whooz.core.Timings
import fr.arichard.whooz.ui.FrameClock
import fr.arichard.whooz.ui.Haptics
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Transparent layer over the PreviewView: rings around the tracked faces, the arming arcs, the
 * countdown, the eliminations and the winner emphasis, all drawn from the latest [FaceFrame] and
 * the pure [ChooserEngine] without allocating.
 *
 * The view is driven by two clocks: frames from [FaceSource] (about 15 Hz, only while the
 * analyzer is on) and the vsync [FrameClock], which runs only while something animates (arming
 * arc, countdown, eliminations, flood, winner pulse, re-arm fade). In a steady IDLE or RESULT no
 * frame is drawn unless a new face frame arrives.
 *
 * Participant positions are snapshotted at round start and kept in the `round*` arrays: during
 * ELIMINATING/RESULT the analyzer is off (rings freeze where they were), and even during the
 * countdown a face that vanishes keeps its ring at its last position, so nobody can dodge the
 * draw by ducking. Late faces are drawn as thin dim rings and never take part.
 *
 * Haptics, hint, chrome and analyzer changes are emitted from [tick] and the event handlers by
 * comparing the desired state with what was last emitted, never from `onDraw`.
 *
 * Between `onPause` ([resetAll]) and the next `onResume` ([configure]) the view is paused: the
 * frame that was already inside ML Kit when the analyzer stopped, or any frame of a screen that
 * stays visible while paused (Recents, a dialog on top), is stored but neither fed to the gate
 * nor drawn, so no round can start, buzz and die while nobody is looking. [configure] rebuilds
 * the engine and gate and resets every piece of emitted-state bookkeeping and every pending
 * timer with them, so a replaced engine never sits under a stale hint, a dark chrome or an
 * analyzer nobody will re-enable.
 *
 * Two sleep paths release the camera: a result left untouched for `resultIdleTimeoutMs`, and
 * an IDLE screen that saw no confirmed face, tap or round for [IDLE_SLEEP_MS] ([IdleSleep]). Both
 * end in [enterSleep]; a tap rebinds. Nothing is drawn while nothing changed: an empty frame
 * after an empty view does not invalidate, so an idle camera in an empty room costs no redraws.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), FrameClock.Listener {

    /** Everything the overlay asks of its activity; UI thread. */
    interface Host {
        /** One of the HINT_* constants. */
        fun onHintChanged(hint: Int)

        /** Buttons and pill shown only in IDLE. */
        fun onChromeVisible(visible: Boolean)

        /** Face detection off while a round is decided, on again when re-armed. */
        fun onAnalyzerEnabled(enabled: Boolean)

        /** A result sat untouched for the idle timeout: release the camera. */
        fun onCameraSleep()

        /** A tap while asleep: bind the camera again. */
        fun onCameraWake()

        /**
         * Someone is using the screen: a tap, a change of the confirmed-face set or a round
         * start. The host re-arms its keep-screen-on timer on it, as the finger arena does on
         * every touch.
         */
        fun onInteraction()
    }

    var host: Host? = null

    /** Sub-mode used by the next round; the pill writes it. */
    var mode: Mode = Mode.DEFAULT

    /** Debug rectangles (analysis bounds and raw ML Kit boxes); only meaningful in debug builds. */
    var debugEnabled: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private val random = Random(SecureRandom().nextLong())
    private var timings: Timings = Timings.camera(StabilizationPreset.NORMAL)
    private var engine = ChooserEngine(timings, random, abortOnLift = false)
    private var gate = StabilityGate(timings.stableMs, timings.collapseMs)
    private var haptics = Haptics(context, false)
    private val clock = FrameClock(this, this)
    private val comeCloser = ComeCloser(COME_CLOSER_MIN_DP * density, COME_CLOSER_HOLD_MS)
    private val idleSleep = IdleSleep(IDLE_SLEEP_MS)

    private var frame: FaceFrame = FaceFrame()
    private var nowMs = 0L
    private var sleeping = false

    /** Between [resetAll] and [configure]: frames are stored but ignored, nothing is drawn. */
    private var paused = true

    /** True while [idleRunnable] is posted; the lazy timer is never posted twice. */
    private var idleCheckPosted = false

    /** Set version of the last frame handled; a change is an interaction for the host. */
    private var lastSetVersion = 0

    /** True when the last `onDraw` drew nothing, so an empty frame need not redraw. */
    private var drawnEmpty = false

    // Round snapshot, indexed by slot.
    private val roundActive = BooleanArray(Palette.SIZE)
    private val roundCx = FloatArray(Palette.SIZE)
    private val roundCy = FloatArray(Palette.SIZE)
    private val roundRadius = FloatArray(Palette.SIZE)
    private val participants = IntArray(Palette.SIZE)
    private var roundLive = false

    // Emitted-state bookkeeping for the transition detection.
    private var countdownTicksSent = 0
    private var elimTicksSent = 0
    private var heavyFired = false
    private var resultHoldElapsed = false
    private var closeHint = false
    private var hint = -1
    private var chromeVisible = true
    private var analyzerWanted = true

    // Dimensions in pixels.
    private val ringStroke = RING_STROKE_DP * density
    private val ringStrokeCountdown = RING_STROKE_COUNTDOWN_DP * density
    private val ringStrokeWinner = RING_STROKE_WINNER_DP * density
    private val glowStroke = GLOW_STROKE_DP * density
    private val thinStroke = THIN_STROKE_DP * density
    private val arcStroke = ARC_STROKE_DP * density
    private val arcOffset = ARC_OFFSET_DP * density
    private val innerInset = INNER_INSET_DP * density
    private val innerStroke = INNER_STROKE_DP * density
    private val badgeRadius = BADGE_DIAMETER_DP * density * 0.5f
    private val badgeGap = BADGE_GAP_DP * density
    private val bandHeight = BAND_HEIGHT_DP * density
    private val minRadius = MIN_RADIUS_DP * density
    private var maxRadius = minRadius * 4f

    // Paints and scratch, all created once.
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = glowStroke }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ringStroke }
    private val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = thinStroke }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = arcStroke
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = innerStroke }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val debugBoundsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = DEBUG_BOUNDS_COLOR
    }
    private val debugBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = DEBUG_BOX_COLOR
    }
    private val arcRect = RectF()

    // Debug geometry, rebuilt in onFrameArrived (never in onDraw).
    private val debugBoundsLines = FloatArray(16)
    private var debugBoundsValid = false
    private val debugBoxLines = FloatArray(FaceObservations.CAPACITY * 16)
    private var debugBoxCount = 0
    private val debugCorners = FloatArray(8)

    private val sleepRunnable = Runnable { enterSleep() }
    private val idleRunnable = Runnable { checkIdle() }
    private val holdRunnable = Runnable {
        resultHoldElapsed = true
        syncDerived(SystemClock.uptimeMillis())
    }

    // ---- Configuration -----------------------------------------------------------------

    /**
     * Applies the settings snapshot; call from `onResume`. A new engine and gate are built
     * because their timings are immutable; the draw loop then never reads preferences. The
     * replacement goes through the same [reset] as `onPause`: whatever the old engine was doing
     * (a round that ran while the screen was paused but visible, see the class doc) must leave
     * no timer, hint, chrome state or analyzer request behind, otherwise the fresh IDLE engine
     * would sit under "Tap to play again" with the analyzer off and nothing to turn it on.
     */
    fun configure(timings: Timings, haptics: Haptics, mode: Mode) {
        this.timings = timings
        this.haptics = haptics
        this.mode = mode
        engine = ChooserEngine(timings, random, abortOnLift = false)
        gate = StabilityGate(timings.stableMs, timings.collapseMs)
        paused = false
        reset(SystemClock.uptimeMillis())
    }

    /** Flips the debug drawing; returns the new state so the activity can tell the source. */
    fun toggleDebug(): Boolean {
        debugEnabled = !debugEnabled
        if (!debugEnabled) {
            debugBoundsValid = false
            debugBoxCount = 0
        }
        invalidate()
        return debugEnabled
    }

    /**
     * `onPause`: no round, no loop, no pending timer, the analyzer wanted again so the next
     * bind starts detecting whatever phase the screen was in, and the view paused so the frames
     * that still trickle in change nothing until [configure].
     */
    fun resetAll() {
        paused = true
        reset(SystemClock.uptimeMillis())
    }

    /** The common ground of [configure] and [resetAll]: back to a clean IDLE, bookkeeping included. */
    private fun reset(now: Long) {
        nowMs = now
        removeCallbacks(sleepRunnable)
        removeCallbacks(holdRunnable)
        removeCallbacks(idleRunnable)
        idleCheckPosted = false
        engine.abort(now)
        clearRound()
        gate.reset()
        comeCloser.reset()
        closeHint = false
        frame.clear()
        lastSetVersion = frame.setVersion
        sleeping = false
        countdownTicksSent = 0
        elimTicksSent = 0
        heavyFired = false
        resultHoldElapsed = false
        clock.stop()
        syncDerived(now)
        invalidate()
    }

    // ---- Inputs ------------------------------------------------------------------------

    /**
     * A new tracker frame, on the UI thread. In IDLE it feeds the gate (and starts the round
     * when armed); during the countdown it moves the frozen participants' rings; afterwards it
     * only refreshes the late faces. Redraws when the picture can have changed: a frame with
     * faces, a frame that empties a view that showed rings, or anything but a steady IDLE.
     */
    fun onFrameArrived(frame: FaceFrame, debug: FaceSource.DebugBoxes?) {
        this.frame = frame
        if (sleeping || paused) return
        val now = SystemClock.uptimeMillis()
        nowMs = now
        if (frame.setVersion != lastSetVersion) {
            lastSetVersion = frame.setVersion
            host?.onInteraction()
        }
        // A confirmed face is presence; the first frame after a reset also arms the lazy timer
        // so an empty room still goes to sleep.
        if (frame.confirmedCount > 0 || !idleCheckPosted) noteActivity(now)
        when (engine.phase) {
            ChooserEngine.Phase.IDLE -> {
                gate.update(frame.confirmedCount, frame.setVersion, now)
                closeHint = comeCloser.update(frame, now)
                if (gate.armed(now)) startRound(now)
            }
            ChooserEngine.Phase.COUNTDOWN -> {
                for (i in 0 until frame.count) {
                    val s = frame.slot[i]
                    if (roundActive[s]) {
                        roundCx[s] = frame.cx[i]
                        roundCy[s] = frame.cy[i]
                        roundRadius[s] = frame.radius[i]
                    }
                }
            }
            else -> { /* frozen: the analyzer is off, a last in-flight frame changes nothing */ }
        }
        if (debugEnabled) rebuildDebug(frame, debug)
        syncDerived(now)
        updateLoop(now)
        if (frame.count > 0 || !drawnEmpty || debugEnabled || engine.phase != ChooserEngine.Phase.IDLE) invalidate()
    }

    override fun onFrame(frameTimeMs: Long) {
        nowMs = frameTimeMs
        tick(frameTimeMs)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> tap()
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** A tap on the overlay: wakes a sleeping camera, or re-arms a finished round. */
    fun tap() {
        if (paused) return
        val now = SystemClock.uptimeMillis()
        nowMs = now
        host?.onInteraction()
        if (sleeping) {
            sleeping = false
            host?.onCameraWake()
            noteActivity(now)
            syncDerived(now)
            invalidate()
            return
        }
        noteActivity(now)
        if (engine.phase == ChooserEngine.Phase.RESULT && engine.requestRearm(true, now)) {
            removeCallbacks(sleepRunnable)
            haptics.tick(this)
            tick(now)
            invalidate()
        }
    }

    // ---- Idle sleep --------------------------------------------------------------------

    /**
     * Presence at [now]: pushes the idle sleep back and makes sure one check is posted. Costs
     * an assignment per frame; the Handler is touched only when no check is pending.
     */
    private fun noteActivity(now: Long) {
        idleSleep.activity(now)
        if (!idleCheckPosted && !paused && !sleeping) {
            idleCheckPosted = true
            postDelayed(idleRunnable, idleSleep.delayMs(now))
        }
    }

    /**
     * The lazy check: sleeps when the timeout elapsed, re-posts for the remainder otherwise.
     * Outside IDLE it simply stops: a result has its own sleep timer, and the return to IDLE
     * ([syncDerived]) re-arms this one.
     */
    private fun checkIdle() {
        idleCheckPosted = false
        if (paused || sleeping || engine.phase != ChooserEngine.Phase.IDLE) return
        val now = SystemClock.uptimeMillis()
        val delay = idleSleep.delayMs(now)
        if (delay == 0L) {
            enterSleep()
        } else {
            idleCheckPosted = true
            postDelayed(idleRunnable, delay)
        }
    }

    // ---- State machine -----------------------------------------------------------------

    private fun startRound(now: Long) {
        var n = 0
        for (i in 0 until frame.count) {
            if (!frame.confirmed[i]) continue
            participants[n] = frame.slot[i]
            n++
        }
        if (n < 2 || !engine.start(participants, n, mode, now)) return
        for (i in 0 until frame.count) {
            val s = frame.slot[i]
            if (!engine.isParticipant(s)) continue
            roundActive[s] = true
            roundCx[s] = frame.cx[i]
            roundCy[s] = frame.cy[i]
            roundRadius[s] = frame.radius[i]
        }
        roundLive = true
        gate.reset()
        comeCloser.reset()
        closeHint = false
        host?.onInteraction()
        // The confirm doubles as the countdown's first tick.
        haptics.confirm(this)
        countdownTicksSent = 1
        elimTicksSent = 0
        heavyFired = false
        resultHoldElapsed = false
        tick(now)
    }

    /** One step of the clocks: advances the engine, emits what changed, decides the loop. */
    private fun tick(now: Long) {
        if (paused) return
        engine.tick(now)
        if (engine.phase == ChooserEngine.Phase.IDLE && !sleeping && gate.armed(now)) {
            startRound(now)
            return
        }
        emitHaptics(now)
        syncDerived(now)
        updateLoop(now)
    }

    private fun emitHaptics(now: Long) {
        when (engine.phase) {
            ChooserEngine.Phase.COUNTDOWN -> {
                val due = engine.hapticTicksDue(now)
                while (countdownTicksSent < due) {
                    haptics.tick(this)
                    countdownTicksSent++
                }
            }
            ChooserEngine.Phase.ELIMINATING -> {
                var due = 0
                val start = engine.eliminationStartMs()
                for (i in 0 until engine.eliminationCount) {
                    if (start + i * timings.elimStepMs <= now) due++
                }
                while (elimTicksSent < due) {
                    haptics.tick(this)
                    elimTicksSent++
                }
            }
            ChooserEngine.Phase.RESULT -> {
                // Ticks missed by a stalled frame are not replayed: the heavy click covers them.
                elimTicksSent = engine.eliminationCount
                if (!heavyFired) {
                    heavyFired = true
                    haptics.heavy()
                    removeCallbacks(holdRunnable)
                    removeCallbacks(sleepRunnable)
                    postDelayed(holdRunnable, timings.resultHoldMs)
                    postDelayed(sleepRunnable, timings.resultIdleTimeoutMs)
                }
            }
            else -> {}
        }
    }

    /** Compares the wanted hint/chrome/analyzer/round state with what was last emitted. */
    private fun syncDerived(now: Long) {
        val phase = engine.phase
        if (phase == ChooserEngine.Phase.IDLE && roundLive) {
            clearRound()
            gate.reset()
            // A round just ended: the idle clock starts again from here.
            noteActivity(now)
        }
        val wantAnalyzer = phase == ChooserEngine.Phase.IDLE ||
            phase == ChooserEngine.Phase.COUNTDOWN ||
            phase == ChooserEngine.Phase.FADING
        if (wantAnalyzer != analyzerWanted) {
            analyzerWanted = wantAnalyzer
            host?.onAnalyzerEnabled(wantAnalyzer)
        }
        val wantChrome = phase == ChooserEngine.Phase.IDLE
        if (wantChrome != chromeVisible) {
            chromeVisible = wantChrome
            host?.onChromeVisible(wantChrome)
        }
        val wantHint = desiredHint(now)
        if (wantHint != hint) {
            hint = wantHint
            host?.onHintChanged(wantHint)
        }
    }

    private fun desiredHint(now: Long): Int = when {
        sleeping -> HINT_SLEEPING
        engine.phase == ChooserEngine.Phase.IDLE ->
            if (gate.isAnimating(now)) HINT_ARMING else if (closeHint) HINT_COME_CLOSER else HINT_IDLE
        engine.phase == ChooserEngine.Phase.RESULT -> if (resultHoldElapsed) HINT_RESULT else HINT_ROUND
        else -> HINT_ROUND
    }

    private fun updateLoop(now: Long) {
        if (needsFrames(now)) clock.start() else clock.stop()
    }

    /** The loop runs for every animated phase and, in RESULT, until flood and pulses are done. */
    private fun needsFrames(now: Long): Boolean {
        if (sleeping) return false
        return when (engine.phase) {
            ChooserEngine.Phase.IDLE -> gate.isAnimating(now)
            ChooserEngine.Phase.RESULT ->
                engine.needsFrames(now) || now - engine.resultStartMs < WINNER_PULSE_PERIOD_MS * WINNER_PULSES
            else -> true
        }
    }

    private fun clearRound() {
        for (s in 0 until Palette.SIZE) roundActive[s] = false
        roundLive = false
    }

    /** Releases the camera (untouched result, or nobody in front of it); a tap brings it back. */
    private fun enterSleep() {
        val now = SystemClock.uptimeMillis()
        nowMs = now
        removeCallbacks(sleepRunnable)
        removeCallbacks(idleRunnable)
        idleCheckPosted = false
        engine.abort(now)
        clearRound()
        gate.reset()
        comeCloser.reset()
        closeHint = false
        frame.clear()
        clock.stop()
        sleeping = true
        host?.onCameraSleep()
        syncDerived(now)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        clock.stop()
        removeCallbacks(sleepRunnable)
        removeCallbacks(holdRunnable)
        removeCallbacks(idleRunnable)
        idleCheckPosted = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = if (w < h) w else h
        maxRadius = side * MAX_RADIUS_FRACTION
        if (maxRadius < minRadius) maxRadius = minRadius
    }

    private fun rebuildDebug(frame: FaceFrame, debug: FaceSource.DebugBoxes?) {
        if (debug == null || !debug.transformValid || frame.bufferW <= 0 || frame.bufferH <= 0) {
            debugBoundsValid = false
            debugBoxCount = 0
            return
        }
        val w = frame.bufferW.toFloat()
        val h = frame.bufferH.toFloat()
        debugCorners[0] = 0f; debugCorners[1] = 0f
        debugCorners[2] = w; debugCorners[3] = 0f
        debugCorners[4] = w; debugCorners[5] = h
        debugCorners[6] = 0f; debugCorners[7] = h
        debug.analysisToView.mapPoints(debugCorners)
        quadToLines(debugCorners, 0, debugBoundsLines, 0)
        debugBoundsValid = true
        debugBoxCount = debug.count
        for (i in 0 until debugBoxCount) quadToLines(debug.quads, i * 8, debugBoxLines, i * 16)
    }

    /** Four corners → four segments for `drawLines`. */
    private fun quadToLines(src: FloatArray, srcOffset: Int, dst: FloatArray, dstOffset: Int) {
        for (k in 0 until 4) {
            val next = (k + 1) % 4
            dst[dstOffset + k * 4] = src[srcOffset + k * 2]
            dst[dstOffset + k * 4 + 1] = src[srcOffset + k * 2 + 1]
            dst[dstOffset + k * 4 + 2] = src[srcOffset + next * 2]
            dst[dstOffset + k * 4 + 3] = src[srcOffset + next * 2 + 1]
        }
    }

    // ---- Drawing (no allocation) -------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (sleeping || paused) {
            drawnEmpty = true
            return
        }
        val now = nowMs
        // Only a steady IDLE with no face draws nothing; every other phase paints something.
        drawnEmpty = engine.phase == ChooserEngine.Phase.IDLE && frame.count == 0 && !debugEnabled
        when (engine.phase) {
            ChooserEngine.Phase.IDLE -> drawIdle(canvas, now)
            ChooserEngine.Phase.COUNTDOWN -> {
                drawLateFaces(canvas)
                drawCountdown(canvas, now)
            }
            ChooserEngine.Phase.ELIMINATING -> {
                drawLateFaces(canvas)
                drawEliminating(canvas, now)
            }
            ChooserEngine.Phase.RESULT -> drawResult(canvas, now, 1f)
            ChooserEngine.Phase.FADING -> {
                val t = Easing.progress(engine.phaseElapsedMs(now), timings.rearmFadeMs)
                drawResult(canvas, now, 1f - t)
            }
        }
        if (debugEnabled) drawDebug(canvas)
    }

    private fun drawIdle(canvas: Canvas, now: Long) {
        val arc = gate.arc(now)
        val f = frame
        for (i in 0 until f.count) {
            val slot = f.slot[i]
            val color = Palette.of(slot)
            val r = FaceGeometry.clampRadius(f.radius[i], minRadius, maxRadius)
            if (!f.confirmed[i]) {
                drawThin(canvas, f.cx[i], f.cy[i], r, color, TENTATIVE_ALPHA)
                continue
            }
            drawRing(canvas, f.cx[i], f.cy[i], r, color, f.alpha[i] * IDLE_RING_ALPHA, ringStroke)
            if (arc > 0f) {
                val ar = r + arcOffset
                arcRect.set(f.cx[i] - ar, f.cy[i] - ar, f.cx[i] + ar, f.cy[i] + ar)
                arcPaint.color = Palette.withAlpha(Color.WHITE, f.alpha[i])
                canvas.drawArc(arcRect, -90f, arc * 360f, false, arcPaint)
            }
        }
    }

    /** Faces that showed up after the set was frozen: thin, dim, and ignored by the engine. */
    private fun drawLateFaces(canvas: Canvas) {
        val f = frame
        for (i in 0 until f.count) {
            val slot = f.slot[i]
            if (engine.isParticipant(slot)) continue
            val r = FaceGeometry.clampRadius(f.radius[i], minRadius, maxRadius)
            drawThin(canvas, f.cx[i], f.cy[i], r, Palette.of(slot), LATE_ALPHA)
        }
    }

    private fun drawCountdown(canvas: Canvas, now: Long) {
        val elapsed = engine.phaseElapsedMs(now)
        val t = Easing.progress(elapsed, timings.countdownMs)
        val pulse = Easing.pulse(elapsed, engine.countdownPulsePeriodMs(now))
        val baseScale = 1f + COUNTDOWN_PULSE_SCALE * pulse
        val stroke = Easing.lerp(ringStroke, ringStrokeCountdown, t)
        for (s in 0 until Palette.SIZE) {
            if (!roundActive[s]) continue
            var alpha = 1f
            var scale = baseScale
            val dimAt = engine.dimAtMs(s)
            if (dimAt >= 0L && now >= dimAt) {
                val d = Easing.easeOutCubic(Easing.progress(now - dimAt, DIM_ANIM_MS))
                alpha = Easing.lerp(1f, LOSER_ALPHA, d)
                scale *= Easing.lerp(1f, LOSER_SCALE, d)
            }
            val r = FaceGeometry.clampRadius(roundRadius[s], minRadius, maxRadius) * scale
            drawRing(canvas, roundCx[s], roundCy[s], r, Palette.of(s), alpha, stroke)
        }
    }

    private fun drawEliminating(canvas: Canvas, now: Long) {
        for (s in 0 until Palette.SIZE) {
            if (!roundActive[s]) continue
            var alpha = 1f
            var scale = 1f
            val at = engine.eliminatedAtMs(s)
            if (at >= 0L) {
                if (now >= at) {
                    val e = Easing.progress(now - at, timings.elimAnimMs)
                    if (e >= 1f) continue
                    scale = LOSER_SCALE * (1f - Easing.easeInQuad(e))
                    alpha = LOSER_ALPHA * (1f - e)
                } else {
                    scale = LOSER_SCALE
                    alpha = LOSER_ALPHA
                }
            }
            val r = FaceGeometry.clampRadius(roundRadius[s], minRadius, maxRadius) * scale
            if (r <= 0f) continue
            drawRing(canvas, roundCx[s], roundCy[s], r, Palette.of(s), alpha, ringStrokeCountdown)
        }
    }

    /**
     * Winner tint (35 %) and bottom band, split into one column per winner, then the winners'
     * rings: 10 dp with a white inner ring pulsing [WINNER_PULSES] times. [fade] is 1 in RESULT
     * and shrinks to 0 during FADING.
     */
    private fun drawResult(canvas: Canvas, now: Long, fade: Float) {
        val elapsed = now - engine.resultStartMs
        val flood = Easing.easeOutCubic(Easing.progress(elapsed, timings.floodMs))
        val w = width.toFloat()
        val h = height.toFloat()
        val winners = engine.winnerCount
        if (winners > 0) {
            val columnWidth = w / winners
            var k = 0
            for (s in 0 until Palette.SIZE) {
                if (!engine.isWinner(s)) continue
                val color = Palette.of(s)
                val left = k * columnWidth
                val right = left + columnWidth
                fillPaint.color = Palette.withAlpha(color, TINT_ALPHA * flood * fade)
                canvas.drawRect(left, 0f, right, h, fillPaint)
                fillPaint.color = Palette.withAlpha(color, BAND_ALPHA * fade)
                canvas.drawRect(left, h - bandHeight * flood, right, h, fillPaint)
                k++
            }
        }
        val innerAlpha = if (elapsed < WINNER_PULSE_PERIOD_MS * WINNER_PULSES) {
            INNER_ALPHA_MIN + (1f - INNER_ALPHA_MIN) * Easing.pulse(elapsed, WINNER_PULSE_PERIOD_MS)
        } else {
            1f
        }
        for (s in 0 until Palette.SIZE) {
            if (!roundActive[s] || !engine.isWinner(s)) continue
            val r = FaceGeometry.clampRadius(roundRadius[s], minRadius, maxRadius)
            drawRing(canvas, roundCx[s], roundCy[s], r, Palette.of(s), fade, ringStrokeWinner)
            val inner = r - innerInset
            if (inner > 0f) {
                innerPaint.color = Palette.withAlpha(Color.WHITE, innerAlpha * fade)
                canvas.drawCircle(roundCx[s], roundCy[s], inner, innerPaint)
            }
        }
    }

    /** Glow + ring + the small color badge above it. */
    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Float, stroke: Float) {
        glowPaint.color = Palette.withAlpha(color, GLOW_ALPHA * alpha)
        canvas.drawCircle(cx, cy, r, glowPaint)
        ringPaint.strokeWidth = stroke
        ringPaint.color = Palette.withAlpha(color, alpha)
        canvas.drawCircle(cx, cy, r, ringPaint)
        badgePaint.color = Palette.withAlpha(color, alpha)
        canvas.drawCircle(cx, cy - r - badgeGap - badgeRadius, badgeRadius, badgePaint)
    }

    private fun drawThin(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
        thinPaint.color = Palette.withAlpha(color, alpha)
        canvas.drawCircle(cx, cy, r, thinPaint)
    }

    private fun drawDebug(canvas: Canvas) {
        if (debugBoundsValid) canvas.drawLines(debugBoundsLines, 0, 16, debugBoundsPaint)
        if (debugBoxCount > 0) canvas.drawLines(debugBoxLines, 0, debugBoxCount * 16, debugBoxPaint)
    }

    companion object {
        const val HINT_IDLE = 0
        const val HINT_ARMING = 1
        const val HINT_ROUND = 2
        const val HINT_RESULT = 3
        const val HINT_COME_CLOSER = 4
        const val HINT_SLEEPING = 5

        // Geometry (dp).
        private const val RING_STROKE_DP = 6f
        private const val RING_STROKE_COUNTDOWN_DP = 8f
        private const val RING_STROKE_WINNER_DP = 10f
        private const val GLOW_STROKE_DP = 18f
        private const val THIN_STROKE_DP = 2f
        private const val ARC_STROKE_DP = 3f
        private const val ARC_OFFSET_DP = 10f
        private const val INNER_INSET_DP = 10f
        private const val INNER_STROKE_DP = 3f
        private const val BADGE_DIAMETER_DP = 18f
        private const val BADGE_GAP_DP = 8f
        private const val BAND_HEIGHT_DP = 72f
        private const val MIN_RADIUS_DP = 24f
        private const val MAX_RADIUS_FRACTION = 0.6f

        /** "Come closer" when every confirmed ring is under 60 dp for 3 s. */
        const val COME_CLOSER_MIN_DP = 60f
        const val COME_CLOSER_HOLD_MS = 3_000L

        /**
         * An IDLE screen with no confirmed face, tap or round for three minutes releases the
         * camera: the same patience as the finger arena's keep-screen-on, so a phone propped up
         * before the players arrive does not stream and detect for hours.
         */
        const val IDLE_SLEEP_MS = 3L * 60L * 1000L

        // Alphas and scales.
        private const val GLOW_ALPHA = 0.25f
        private const val IDLE_RING_ALPHA = 0.9f
        private const val TENTATIVE_ALPHA = 0.45f
        private const val LATE_ALPHA = 0.35f
        private const val LOSER_ALPHA = 0.55f
        private const val LOSER_SCALE = 0.92f
        private const val COUNTDOWN_PULSE_SCALE = 0.15f
        private const val TINT_ALPHA = 0.35f
        private const val BAND_ALPHA = 0.9f
        private const val INNER_ALPHA_MIN = 0.3f
        private const val DIM_ANIM_MS = 150L

        /** The winner's inner ring pulses three times at 600 ms. */
        const val WINNER_PULSE_PERIOD_MS = 600L
        const val WINNER_PULSES = 3

        private const val DEBUG_BOUNDS_COLOR = 0xFFFF3B6B.toInt()
        private const val DEBUG_BOX_COLOR = 0xFF3BE8FF.toInt()
    }
}
