package fr.arichard.whooz.finger

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.arichard.whooz.core.ChooserEngine
import fr.arichard.whooz.core.ChooserEngine.Phase
import fr.arichard.whooz.core.Easing
import fr.arichard.whooz.core.Mode
import fr.arichard.whooz.core.Palette
import fr.arichard.whooz.core.StabilityGate
import fr.arichard.whooz.core.StabilizationPreset
import fr.arichard.whooz.core.Timings
import fr.arichard.whooz.core.TouchTracker
import fr.arichard.whooz.ui.FrameClock
import fr.arichard.whooz.ui.Haptics
import java.security.SecureRandom
import kotlin.random.Random

/**
 * The finger arena: every player keeps one finger on the screen, the circles arm, pulse,
 * vanish one by one and the winner's color floods the screen.
 *
 * The view is thin glue around three pure classes: [TouchTracker] (which finger owns which
 * color slot), [StabilityGate] (has the group held still long enough) and [ChooserEngine]
 * (the round itself). Every animation is a function of the frame time minus a phase start,
 * so a replayed clock draws the same picture and nothing has to be "animated" by hand. The
 * [FrameClock] runs only while something moves: idle, touching-without-arming and a steady
 * result cost zero frames, which is the battery rule of the design brief.
 *
 * Hot paths ([onTouchEvent], [onFrame], [onDraw]) allocate nothing: every Paint, rect,
 * array and string is a field built in the constructor or in [onSizeChanged].
 *
 * The view knows nothing about the activity: hints, chrome visibility, completed rounds and
 * touch activity (for the keep-screen-on timer) go through the small [Listener].
 */
class FingerChooserView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), FrameClock.Listener {

    /** What the host needs to know; every method runs on the UI thread. */
    interface Listener {
        /** One of [HINT_IDLE], [HINT_ONE_MORE], [HINT_ARMING], [HINT_ROUND], [HINT_RESULT_HOLD], [HINT_TOO_MANY]. */
        fun onHintChanged(state: Int)

        /** True only in IDLE with no finger down: the only moment the chrome may be shown. */
        fun onChromeAllowed(allowed: Boolean)

        /** A round reached its result (aborted rounds never call this). */
        fun onRoundCompleted()

        /** A first finger landed (ACTION_DOWN): resets the keep-screen-on timer. */
        fun onTouchActivity()
    }

    var listener: Listener? = null

    private val tracker = TouchTracker()
    private val random: Random = Random(SecureRandom().nextLong())
    private var timings: Timings = Timings.finger(StabilizationPreset.NORMAL)
    private var engine = ChooserEngine(timings, random, abortOnLift = true)
    private var gate = StabilityGate(timings.stableMs, timings.collapseMs)
    private val clock = FrameClock(this, this)
    private var haptics: Haptics? = null
    private var mode: Mode = Mode.DEFAULT

    // ---- Per-slot state (slot = color index, 0 until Palette.SIZE) -----------------------

    /**
     * Last known position of the finger owning each slot; survives a lift so a frozen
     * participant keeps its ring. During FADING the entries of the round's participants are
     * pinned (see [keepsResultGeometry]): every slot is free again by then, so a finger
     * landing mid-fade reuses a winner's slot and must not drag the fading flood and winner
     * ring onto itself.
     */
    private val slotX = FloatArray(Palette.SIZE)
    private val slotY = FloatArray(Palette.SIZE)

    /** When the finger landed, for the appear pop. */
    private val appearMs = LongArray(Palette.SIZE)

    /** Tracker index owning each slot as a participant, or -1; refreshed once per draw. */
    private val slotIndex = IntArray(Palette.SIZE)

    /** Scratch for [TouchTracker.participantsSnapshot]. */
    private val snapshot = IntArray(Palette.SIZE)

    // ---- Timeline --------------------------------------------------------------------------

    /** Clock of the last touch or frame; [onDraw] reads only this so a stopped loop never drifts. */
    private var nowMs = 0L
    private var lastAppearMs = FAR_PAST
    private var abortAtMs = FAR_PAST

    /** The gate is not fed before this instant so it restarts after the abort collapse. */
    private var gateFrozenUntilMs = 0L
    private var seenTransitions = 0
    private var countdownTicksEmitted = 0
    private var elimTicksEmitted = 0
    private var tooMany = false

    /** Last state reported through the listener. */
    var hintState: Int = HINT_IDLE
        private set
    var isChromeAllowed: Boolean = true
        private set

    /** Fires once the result hold elapsed, so a lifted-early group fades without a touch. */
    private val holdRunnable = Runnable {
        advance(SystemClock.uptimeMillis())
        if (!clock.isRunning) invalidate()
    }

    // ---- Geometry (px) ---------------------------------------------------------------------

    private val density = resources.displayMetrics.density
    private val ringRadius = dp(44f)
    private val discRadius = dp(34f)
    private val ringStroke = dp(5f)
    private val ringStrokeThick = dp(8f)
    private val arcRadius = dp(51f)
    private val glowWidthInner = dp(6f)
    private val glowWidthMid = dp(12f)
    private val glowWidthOuter = dp(18f)
    private val winnerRadius = dp(56f)
    private val winnerStroke = dp(6f)
    private val dotRadius = dp(4f)
    private val gridStep = dp(48f)
    private val bottomZone = dp(24f)
    private val edgeFallback = dp(32f).toInt()
    private val baseTextSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics,
    )

    // ---- Paints, rects and strings (never created in a hot path) -------------------------

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val gridMinorPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = GRID_MINOR_COLOR
    }
    private val gridMajorPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = GRID_MAJOR_COLOR
    }
    private val gradientPaint = Paint()
    private val floodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bandPaint = Paint().apply { style = Paint.Style.FILL }
    private val winnerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = winnerStroke
    }
    private val winnerInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DOT_COLOR
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = baseTextSize
        color = TEXT_COLOR
    }

    /** Vertical offset centring a digit on its anchor, at the base size (scaled with the pop). */
    private val textOffsetY: Float = textPaint.fontMetrics.let { -(it.ascent + it.descent) / 2f }

    private val arcRect = RectF()
    private val bottomRect = RectF()
    private val bandRect = RectF()
    private val exclusionLeft = Rect()
    private val exclusionRight = Rect()
    private val exclusionRects: List<Rect> = listOf(exclusionLeft, exclusionRight)

    /** "1".."16" precomputed: Order mode draws numbers without building strings. */
    private val numbers: Array<String> = Array(Palette.SIZE + 1) { it.toString() }

    private var gridMinor = FloatArray(0)
    private var gridMinorCount = 0
    private var gridMajor = FloatArray(0)
    private var gridMajorCount = 0

    init {
        for (s in 0 until Palette.SIZE) slotIndex[s] = -1
        isFocusable = false
    }

    // ---- Host API ------------------------------------------------------------------------

    /** Sub-mode of the next round; a running round keeps the mode it started with. */
    fun setMode(mode: Mode) {
        this.mode = mode
    }

    /**
     * Rebuilds the gate and engine for new timings. Call only while idle (the host does it in
     * `onResume`, after `onPause` reset everything): a running round would be dropped.
     */
    fun setTimings(timings: Timings) {
        if (timings == this.timings) return
        this.timings = timings
        engine = ChooserEngine(timings, random, abortOnLift = true)
        gate = StabilityGate(timings.stableMs, timings.collapseMs)
        seenTransitions = 0
    }

    fun setHaptics(haptics: Haptics?) {
        this.haptics = haptics
    }

    /** Back pressed mid-round: forget every finger, abort the engine, no winner. */
    fun cancelRound() {
        cancelRound(SystemClock.uptimeMillis())
    }

    /** `onPause`: everything back to IDLE, loop stopped, no pending callback. */
    fun resetAll() {
        cancelRound(SystemClock.uptimeMillis())
        clock.stop()
        removeCallbacks(holdRunnable)
        lastAppearMs = FAR_PAST
        abortAtMs = FAR_PAST
        gateFrozenUntilMs = 0L
    }

    // ---- Touch ---------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                listener?.onTouchActivity()
                pointerDown(event, now)
            }
            MotionEvent.ACTION_POINTER_DOWN -> pointerDown(event, now)
            MotionEvent.ACTION_MOVE -> {
                // Only the latest sample matters: historical points would be drawn never.
                var moved = false
                val n = event.pointerCount
                for (i in 0 until n) {
                    if (tracker.move(event.getPointerId(i), event.getX(i), event.getY(i))) moved = true
                }
                if (moved && !clock.isRunning) invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> pointerUp(event, now, last = false)
            MotionEvent.ACTION_UP -> pointerUp(event, now, last = true)
            MotionEvent.ACTION_CANCEL -> cancelRound(now)
        }
        return true
    }

    private fun pointerDown(event: MotionEvent, now: Long) {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        if (event.pointerCount > TouchTracker.MAX_POINTERS) tooMany = true
        val phase = engine.phase
        // A finger landing in a running round is absorbed: tracked (dim dot) but never a
        // participant, so a bystander can neither join late nor erase the result.
        val absorb = phase == Phase.COUNTDOWN || phase == Phase.ELIMINATING || phase == Phase.RESULT
        val x = event.getX(index)
        val y = event.getY(index)
        if (tracker.down(id, x, y, event.getToolType(index), absorb) && !absorb) {
            val slot = tracker.slotAt(tracker.indexOfId(id))
            appearMs[slot] = now
            lastAppearMs = now
            if (!keepsResultGeometry(phase, slot)) {
                slotX[slot] = x
                slotY[slot] = y
            }
            haptics?.tick(this)
        }
        touchChanged(now)
    }

    private fun pointerUp(event: MotionEvent, now: Long, last: Boolean) {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        val cancelled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (event.flags and MotionEvent.FLAG_CANCELED) != 0
        val i = tracker.indexOfId(id)
        val wasParticipant = i >= 0 && !tracker.isAbsorbed(i)
        val slot = tracker.up(id, cancelled)
        if (event.pointerCount - 1 <= TouchTracker.MAX_POINTERS) tooMany = false
        if (slot >= 0 && wasParticipant) {
            if (engine.phase == Phase.COUNTDOWN) {
                // Chwazi rule: a frozen participant lifting early restarts the gathering.
                if (engine.participantLifted(slot, now)) onAbort(now)
            } else if (engine.phase == Phase.IDLE && !cancelled) {
                haptics?.tick(this)
            }
        }
        if (last) {
            // Pointer ids are only unique within one gesture.
            tracker.clear()
            tooMany = false
        }
        touchChanged(now)
    }

    private fun touchChanged(now: Long) {
        advance(now)
        invalidate()
    }

    private fun onAbort(now: Long) {
        abortAtMs = now
        gateFrozenUntilMs = now + timings.collapseMs
        gate.reset()
    }

    private fun cancelRound(now: Long) {
        tracker.clear()
        tooMany = false
        engine.abort(now)
        gate.reset()
        advance(now)
        invalidate()
    }

    // ---- Timeline ------------------------------------------------------------------------

    override fun onFrame(frameTimeMs: Long) {
        advance(frameTimeMs)
    }

    /**
     * One step of the arena at [t]: advances the engine, feeds the gate, fires haptics and
     * host callbacks on the transitions it detects, then decides whether the loop must run.
     * Called from touches, frames and the hold callback; never from [onDraw].
     */
    private fun advance(t: Long) {
        // Touch and vsync clocks are both uptime-based but a vsync timestamp can trail an
        // event by a few ms; the arena clock never goes backwards.
        val now = if (t > nowMs) t else nowMs
        nowMs = now
        engine.tick(now)
        detectTransitions()
        when (engine.phase) {
            Phase.IDLE -> feedGate(now)
            Phase.RESULT -> if (tracker.count == 0 && engine.requestRearm(true, now)) detectTransitions()
            else -> {}
        }
        emitRoundTicks(now)
        notifyStates(now)
        scheduleFrames(now)
    }

    private fun feedGate(now: Long) {
        if (now < gateFrozenUntilMs) return
        gate.update(tracker.participantCount, tracker.setVersion, now)
        if (!gate.armed(now)) return
        val n = tracker.participantsSnapshot(snapshot)
        if (engine.start(snapshot, n, mode, now)) {
            countdownTicksEmitted = 0
            elimTicksEmitted = 0
            haptics?.confirm(this)
            detectTransitions()
        }
        gate.reset()
    }

    /** Phase changes since the last look; the engine has no listeners on purpose. */
    private fun detectTransitions() {
        val tc = engine.transitionCount
        if (tc == seenTransitions) return
        seenTransitions = tc
        when (engine.phase) {
            Phase.RESULT -> {
                haptics?.heavy()
                removeCallbacks(holdRunnable)
                postDelayed(holdRunnable, timings.resultHoldMs)
                listener?.onRoundCompleted()
            }
            Phase.IDLE -> {
                removeCallbacks(holdRunnable)
                gate.reset()
            }
            else -> {}
        }
    }

    /**
     * Countdown quarter ticks and one tick per vanishing (or per number popping in), emitted
     * at most one per step so a skipped frame never turns into a burst.
     */
    private fun emitRoundTicks(now: Long) {
        val phase = engine.phase
        if (phase == Phase.IDLE) return
        val due = engine.hapticTicksDue(now)
        if (due > countdownTicksEmitted) {
            countdownTicksEmitted = due
            haptics?.tick(this)
        }
        if (phase == Phase.COUNTDOWN) return
        val total: Int
        val step: Long
        if (!engine.revealing) {
            total = engine.eliminationCount
            step = timings.elimStepMs
        } else if (engine.mode is Mode.Order) {
            total = engine.participantCount
            step = ChooserEngine.REVEAL_ORDER_STEP_MS
        } else {
            // Teams: one tick when the colors start to turn, a tick per 100 ms would buzz.
            total = 1
            step = ChooserEngine.REVEAL_TEAM_STAGGER_MS
        }
        if (phase != Phase.ELIMINATING) {
            elimTicksEmitted = total
            return
        }
        var count = (engine.phaseElapsedMs(now) / step + 1L).toInt()
        if (count > total) count = total
        if (count > elimTicksEmitted) {
            elimTicksEmitted = count
            haptics?.tick(this)
        }
    }

    private fun notifyStates(now: Long) {
        val holdElapsed = engine.phase == Phase.RESULT && engine.phaseElapsedMs(now) >= timings.resultHoldMs
        val hint = HintResolver.resolve(engine.phase, tracker.participantCount, tooMany, holdElapsed)
        if (hint != hintState) {
            hintState = hint
            listener?.onHintChanged(hint)
        }
        val allowed = engine.phase == Phase.IDLE && tracker.count == 0
        if (allowed != isChromeAllowed) {
            isChromeAllowed = allowed
            listener?.onChromeAllowed(allowed)
        }
    }

    /** The loop runs for the engine's phases plus the view's own transients, nothing else. */
    private fun scheduleFrames(now: Long) {
        val need = engine.needsFrames(now) ||
            gate.isAnimating(now) ||
            now - lastAppearMs < APPEAR_MS ||
            now < gateFrozenUntilMs ||
            now - abortAtMs < timings.collapseMs
        if (need) clock.start() else clock.stop()
    }

    override fun onDetachedFromWindow() {
        clock.stop()
        removeCallbacks(holdRunnable)
        super.onDetachedFromWindow()
    }

    // ---- Layout --------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fw = w.toFloat()
        val fh = h.toFloat()
        bottomRect.set(0f, fh - bottomZone, fw, fh)
        gradientPaint.shader = LinearGradient(
            0f, fh - bottomZone, 0f, fh, GRADIENT_TOP, GRADIENT_BOTTOM, Shader.TileMode.CLAMP,
        )
        buildGrid(fw, fh)
    }

    /**
     * Grid lines every 48 dp, centred so the pattern is symmetric; every fourth line is a
     * touch brighter. Stored as drawLines arrays: one call per paint per frame.
     */
    private fun buildGrid(w: Float, h: Float) {
        val cols = (w / gridStep).toInt()
        val rows = (h / gridStep).toInt()
        val total = cols + rows
        gridMinor = FloatArray(total * 4)
        gridMajor = FloatArray(total * 4)
        var minor = 0
        var major = 0
        val x0 = (w - cols * gridStep) / 2f
        val y0 = (h - rows * gridStep) / 2f
        for (c in 0..cols) {
            val x = x0 + c * gridStep
            if (c % 4 == 0) major = putLine(gridMajor, major, x, 0f, x, h) else minor = putLine(gridMinor, minor, x, 0f, x, h)
        }
        for (r in 0..rows) {
            val y = y0 + r * gridStep
            if (r % 4 == 0) major = putLine(gridMajor, major, 0f, y, w, y) else minor = putLine(gridMinor, minor, 0f, y, w, y)
        }
        gridMinorCount = minor
        gridMajorCount = major
    }

    private fun putLine(a: FloatArray, at: Int, x0: Float, y0: Float, x1: Float, y1: Float): Int {
        if (at + 4 > a.size) return at
        a[at] = x0
        a[at + 1] = y0
        a[at + 2] = x1
        a[at + 3] = y1
        return at + 4
    }

    /**
     * Two full-height edge strips are excluded from the system back gesture so a finger near
     * the edge does not cancel the round. The bottom home zone cannot be excluded; the
     * gradient marks it instead.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val gestures = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.systemGestures())
        var l = gestures?.left ?: 0
        var r = gestures?.right ?: 0
        if (l <= 0) l = edgeFallback
        if (r <= 0) r = edgeFallback
        exclusionLeft.set(0, 0, l, height)
        exclusionRight.set(width - r, 0, width, height)
        ViewCompat.setSystemGestureExclusionRects(this, exclusionRects)
    }

    // ---- Drawing -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val now = nowMs
        val phase = engine.phase
        canvas.drawColor(Palette.BACKGROUND)
        drawGrid(canvas, now, phase)
        canvas.drawRect(bottomRect, gradientPaint)
        refreshSlots()

        val fade = if (phase == Phase.FADING) {
            1f - Easing.progress(engine.phaseElapsedMs(now), timings.rearmFadeMs)
        } else {
            1f
        }
        val flooding = (phase == Phase.RESULT || phase == Phase.FADING) && !engine.revealing

        for (slot in 0 until Palette.SIZE) {
            val i = slotIndex[slot]
            val present = i >= 0
            if (phase == Phase.IDLE) {
                if (present) drawGatheringFinger(canvas, slot, tracker.xAt(i), tracker.yAt(i), now)
            } else if (engine.isParticipant(slot) && !(phase == Phase.FADING && present)) {
                // Winners are drawn after the flood so their ring sits on top of it.
                if (!(flooding && engine.isWinner(slot))) drawRoundFinger(canvas, slot, now, phase, fade)
            } else if (present && phase == Phase.FADING) {
                // A fresh finger landing while the result fades is a normal gathering finger,
                // drawn where it is: slotX/slotY may still hold the fading result's geometry.
                drawGatheringFinger(canvas, slot, tracker.xAt(i), tracker.yAt(i), now)
            }
        }

        for (i in 0 until tracker.count) {
            if (tracker.isAbsorbed(i)) canvas.drawCircle(tracker.xAt(i), tracker.yAt(i), dotRadius, dotPaint)
        }

        if (flooding) {
            if (engine.winnerCount == 1) drawFlood(canvas, now, fade) else drawBands(canvas, now, fade)
            for (slot in 0 until Palette.SIZE) {
                if (engine.isWinner(slot)) drawWinnerRing(canvas, slot, now, fade)
            }
        }
    }

    /** One pass over the tracker: which slot is a live participant, and where. */
    private fun refreshSlots() {
        val phase = engine.phase
        for (s in 0 until Palette.SIZE) slotIndex[s] = -1
        for (i in 0 until tracker.count) {
            if (tracker.isAbsorbed(i)) continue
            val slot = tracker.slotAt(i)
            slotIndex[slot] = i
            if (keepsResultGeometry(phase, slot)) continue
            slotX[slot] = tracker.xAt(i)
            slotY[slot] = tracker.yAt(i)
        }
    }

    /**
     * True while [slot]'s entry in [slotX]/[slotY] belongs to the fading result rather than
     * to the finger currently on it. FADING starts only once every finger lifted, so from
     * then on any non-absorbed tracker entry is a newcomer; in RESULT the opposite holds
     * (newcomers are absorbed) and a winner still holding its finger keeps its ring moving.
     */
    private fun keepsResultGeometry(phase: Phase, slot: Int): Boolean =
        phase == Phase.FADING && engine.isParticipant(slot)

    /** Faint grid while gathering; it dissolves as the countdown starts and returns with the fade. */
    private fun drawGrid(canvas: Canvas, now: Long, phase: Phase) {
        val alpha = when (phase) {
            Phase.IDLE -> 1f
            Phase.COUNTDOWN -> 1f - Easing.easeOutCubic(Easing.progress(engine.phaseElapsedMs(now), GRID_FADE_MS))
            Phase.FADING -> Easing.progress(engine.phaseElapsedMs(now), timings.rearmFadeMs)
            else -> 0f
        }
        if (alpha <= 0f) return
        gridMinorPaint.alpha = (GRID_MINOR_ALPHA * alpha).toInt()
        gridMajorPaint.alpha = (GRID_MAJOR_ALPHA * alpha).toInt()
        if (gridMinorCount > 0) canvas.drawLines(gridMinor, 0, gridMinorCount, gridMinorPaint)
        if (gridMajorCount > 0) canvas.drawLines(gridMajor, 0, gridMajorCount, gridMajorPaint)
    }

    /**
     * A finger while the engine is IDLE (or landing during the fade): appear pop, arming
     * arc, abort collapse. Drawn at the tracker's [x]/[y], never from [slotX]/[slotY].
     */
    private fun drawGatheringFinger(canvas: Canvas, slot: Int, x: Float, y: Float, now: Long) {
        var scale = 1f
        var stroke = ringStroke
        val appear = now - appearMs[slot]
        if (appear < APPEAR_MS) scale = Easing.pop(Easing.progress(appear, APPEAR_MS))
        var arc = gate.arc(now)
        val sinceAbort = now - abortAtMs
        if (sinceAbort < timings.collapseMs) {
            // The armed arcs and thick rings ease back to the gathering look.
            val c = Easing.easeOutCubic(Easing.progress(sinceAbort, timings.collapseMs))
            val collapsing = 1f - c
            if (collapsing > arc) arc = collapsing
            stroke = Easing.lerp(ringStrokeThick, ringStroke, c)
            scale *= Easing.lerp(ABORT_SCALE, 1f, c)
        }
        drawCircleSet(canvas, x, y, Palette.of(slot), 1f, scale, stroke, arc, DISC_ALPHA)
    }

    /** A frozen participant: pulse, dimming, vanishing, number or team color. */
    private fun drawRoundFinger(canvas: Canvas, slot: Int, now: Long, phase: Phase, fade: Float) {
        var alpha = fade
        var scale = 1f
        var stroke = ringStrokeThick
        var color = Palette.of(slot)
        var discAlpha = DISC_ALPHA

        val elimAt = engine.eliminatedAtMs(slot)
        if (elimAt >= 0L && now >= elimAt) {
            val t = Easing.progress(now - elimAt, timings.elimAnimMs)
            if (t >= 1f) return
            scale *= 1f - Easing.easeOutCubic(t)
            alpha *= 1f - t
        } else {
            val dimAt = engine.dimAtMs(slot)
            if (dimAt >= 0L && now >= dimAt) {
                val d = Easing.easeOutCubic(Easing.progress(now - dimAt, DIM_MS))
                alpha *= Easing.lerp(1f, DIM_ALPHA, d)
                scale *= Easing.lerp(1f, DIM_SCALE, d)
            }
        }

        if (phase == Phase.COUNTDOWN) {
            val elapsed = engine.phaseElapsedMs(now)
            scale *= 1f + PULSE_AMPLITUDE * Easing.pulse(elapsed, engine.countdownPulsePeriodMs(now))
            stroke = Easing.lerp(ringStroke, ringStrokeThick, Easing.easeOutCubic(Easing.progress(elapsed, THICKEN_MS)))
        }

        var number = -1
        var textScale = 1f
        if (engine.revealing) {
            val revealAt = engine.revealAtMs(slot)
            if (revealAt >= 0L && now >= revealAt) {
                if (engine.mode is Mode.Order) {
                    number = engine.orderOf(slot)
                    textScale = Easing.pop(Easing.progress(now - revealAt, ChooserEngine.REVEAL_ORDER_STEP_MS))
                } else {
                    val t = Easing.progress(now - revealAt, ChooserEngine.REVEAL_TEAM_FADE_MS)
                    color = ColorUtils.blendARGB(color, Palette.team(engine.teamOf(slot)), t)
                    discAlpha = Easing.lerp(DISC_ALPHA, TEAM_DISC_ALPHA, t)
                }
            }
        }

        val x = slotX[slot]
        val y = slotY[slot]
        drawCircleSet(canvas, x, y, color, alpha, scale, stroke, 0f, discAlpha)
        if (number in 1..Palette.SIZE && textScale > 0.01f) {
            textPaint.textSize = baseTextSize * textScale
            textPaint.color = Palette.withAlpha(TEXT_COLOR, alpha)
            canvas.drawText(numbers[number], x, y + textOffsetY * textScale, textPaint)
        }
    }

    /** Glow halo, filled disc, main ring and arming arc of one finger. */
    private fun drawCircleSet(
        canvas: Canvas,
        x: Float,
        y: Float,
        color: Int,
        alpha: Float,
        scale: Float,
        stroke: Float,
        arc: Float,
        discAlpha: Float,
    ) {
        val r = ringRadius * scale
        // Three overlapping halos stacked outward: 24 % next to the ring, 12 %, then 4 %.
        glowPaint.color = Palette.withAlpha(color, GLOW_OUTER_ALPHA * alpha)
        glowPaint.strokeWidth = glowWidthOuter
        canvas.drawCircle(x, y, r + glowWidthOuter / 2f, glowPaint)
        glowPaint.color = Palette.withAlpha(color, GLOW_MID_ALPHA * alpha)
        glowPaint.strokeWidth = glowWidthMid
        canvas.drawCircle(x, y, r + glowWidthMid / 2f, glowPaint)
        glowPaint.color = Palette.withAlpha(color, GLOW_INNER_ALPHA * alpha)
        glowPaint.strokeWidth = glowWidthInner
        canvas.drawCircle(x, y, r + glowWidthInner / 2f, glowPaint)

        discPaint.color = Palette.withAlpha(color, discAlpha * alpha)
        canvas.drawCircle(x, y, discRadius * scale, discPaint)

        ringPaint.color = Palette.withAlpha(color, alpha)
        ringPaint.strokeWidth = stroke
        canvas.drawCircle(x, y, r, ringPaint)

        if (arc > 0f) {
            val ar = arcRadius * scale
            arcRect.set(x - ar, y - ar, x + ar, y + ar)
            arcPaint.color = Palette.withAlpha(color, alpha)
            canvas.drawArc(arcRect, -90f, 360f * arc, false, arcPaint)
        }
    }

    /** Winner(1): the winner's color grows from the finger until it covers the farthest corner. */
    private fun drawFlood(canvas: Canvas, now: Long, fade: Float) {
        val slot = engine.winnerSlot()
        if (slot < 0) return
        val x = slotX[slot]
        val y = slotY[slot]
        val t = Easing.progress(now - engine.resultStartMs, timings.floodMs)
        val radius = Easing.floodRadius(x, y, width.toFloat(), height.toFloat(), t)
        floodPaint.color = Palette.withAlpha(Palette.of(slot), fade)
        canvas.drawCircle(x, y, radius, floodPaint)
    }

    /** Winner(n > 1): n vertical bands in the winners' colors, grown from the left. */
    private fun drawBands(canvas: Canvas, now: Long, fade: Float) {
        val n = engine.winnerCount
        if (n <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val covered = Easing.easeOutCubic(Easing.progress(now - engine.resultStartMs, timings.floodMs)) * w
        val bandWidth = w / n
        var k = 0
        for (slot in 0 until Palette.SIZE) {
            if (!engine.isWinner(slot)) continue
            val left = k * bandWidth
            var right = left + bandWidth
            if (right > covered) right = covered
            if (right > left) {
                bandRect.set(left, 0f, right, h)
                bandPaint.color = Palette.withAlpha(Palette.of(slot), fade)
                canvas.drawRect(bandRect, bandPaint)
            }
            k++
        }
    }

    /** The white ring that stays on a winner's finger, grown from the main ring at the reveal. */
    private fun drawWinnerRing(canvas: Canvas, slot: Int, now: Long, fade: Float) {
        val g = Easing.lerp(
            ringRadius / winnerRadius, 1f,
            Easing.easeOutCubic(Easing.progress(now - engine.resultStartMs, WINNER_POP_MS)),
        )
        val r = winnerRadius * g
        val x = slotX[slot]
        val y = slotY[slot]
        winnerInnerPaint.color = Palette.withAlpha(Palette.of(slot), fade)
        canvas.drawCircle(x, y, r, winnerInnerPaint)
        winnerRingPaint.color = Palette.withAlpha(TEXT_COLOR, fade)
        canvas.drawCircle(x, y, r, winnerRingPaint)
    }

    private fun dp(value: Float): Float = value * density

    companion object {
        const val HINT_IDLE = HintResolver.IDLE
        const val HINT_ONE_MORE = HintResolver.ONE_MORE
        const val HINT_ARMING = HintResolver.ARMING
        const val HINT_ROUND = HintResolver.ROUND
        const val HINT_RESULT_HOLD = HintResolver.RESULT_HOLD
        const val HINT_TOO_MANY = HintResolver.TOO_MANY

        /** Circle appear pop 0 → 1.08 → 1. */
        const val APPEAR_MS = 220L
        /** Ring thickening 5 → 8 dp at the start of the countdown. */
        private const val THICKEN_MS = 200L
        /** Grid dissolve at the start of the countdown. */
        private const val GRID_FADE_MS = 200L
        /** Loser dimming step once its turn comes in the last 400 ms of the countdown. */
        private const val DIM_MS = 120L
        /** Winner ring growth from the main ring to its final size. */
        private const val WINNER_POP_MS = 220L
        private const val PULSE_AMPLITUDE = 0.15f
        private const val DIM_ALPHA = 0.55f
        private const val DIM_SCALE = 0.92f
        private const val ABORT_SCALE = 1.08f
        private const val DISC_ALPHA = 0.18f
        private const val TEAM_DISC_ALPHA = 0.5f
        private const val GLOW_INNER_ALPHA = 0.12f
        private const val GLOW_MID_ALPHA = 0.08f
        private const val GLOW_OUTER_ALPHA = 0.04f
        private const val GRID_MINOR_ALPHA = 0x12
        private const val GRID_MAJOR_ALPHA = 0x20
        private const val GRID_MINOR_COLOR = 0x12FFFFFF
        private const val GRID_MAJOR_COLOR = 0x20FFFFFF
        private const val GRADIENT_TOP = 0x00FFFFFF
        private const val GRADIENT_BOTTOM = 0x24FFFFFF
        private const val DOT_COLOR = 0x4DFFFFFF
        private const val TEXT_COLOR = 0xFFFFFFFF.toInt()

        /** Far enough in the past that "now - x" never reads as a running transient. */
        private const val FAR_PAST = -1_000_000_000L
    }
}
