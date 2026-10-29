package fr.arichard.whooz.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import fr.arichard.whooz.core.ChooserEngine
import fr.arichard.whooz.core.Easing
import fr.arichard.whooz.core.Mode
import fr.arichard.whooz.core.Palette
import fr.arichard.whooz.core.StabilizationPreset
import fr.arichard.whooz.core.Timings
import fr.arichard.whooz.ui.FrameClock
import fr.arichard.whooz.ui.Haptics
import java.security.SecureRandom
import kotlin.random.Random

/**
 * The roll-call layer over the preview: the ring and lock arc of the one face in front of the
 * camera, the strip of portraits already enrolled, and the draw animated over those portraits
 * (countdown pulse, eliminations, the winner's portrait growing to the centre in a flood of its
 * color). Everything is drawn from the latest [FaceFrame], the pure [RollCall] and the pure
 * [ChooserEngine] without allocating; portraits are bitmaps taken once per lock by the host.
 *
 * Clocks and sleep rules are those of [FaceOverlayView]: face frames at ~15 Hz while the
 * analyzer is on, the vsync [FrameClock] only while something animates, a 3-minute idle sleep
 * that releases the camera (the roster survives it), and a paused state between `onPause` and
 * the next [configure] where frames are stored but ignored. The analyzer is off for the whole
 * round: faces play no part once the portraits are taken.
 */
class RollCallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), FrameClock.Listener {

    /** Everything the view asks of its activity; UI thread. */
    interface Host {
        /** One of the HINT_ constants; [count] is the roster size for the ready hint. */
        fun onHintChanged(hint: Int, count: Int)
        fun onChromeVisible(visible: Boolean)
        fun onAnalyzerEnabled(enabled: Boolean)
        /** The roster changed: Go needs at least two, "new group" needs at least one. */
        fun onRosterChanged(count: Int, canDraw: Boolean)
        /**
         * Takes the portrait of the face just locked, a square of [sizePx] around the ring
         * (view coordinates). Null when the preview has no pixels yet: the player is enrolled
         * with a colored tile instead.
         */
        fun capturePortrait(cx: Float, cy: Float, radius: Float, sizePx: Int): Bitmap?
        fun onInteraction()
        fun onCameraSleep()
        fun onCameraWake()
    }

    var host: Host? = null

    /** Sub-mode used by the next draw; the pill writes it. */
    var mode: Mode = Mode.DEFAULT

    private val density = resources.displayMetrics.density
    private val random = Random(SecureRandom().nextLong())
    private var timings: Timings = Timings.camera(StabilizationPreset.NORMAL)
    private var engine = ChooserEngine(timings, random, abortOnLift = false)
    private val rollCall = RollCall(lockMs(timings), timings.collapseMs, CLEAR_MS)
    private var haptics = Haptics(context, false)
    private val clock = FrameClock(this, this)
    private val idleSleep = IdleSleep(IDLE_SLEEP_MS)

    private var frame: FaceFrame = FaceFrame()
    private var nowMs = 0L
    private var sleeping = false
    private var paused = true
    private var idleCheckPosted = false

    private val portraits = arrayOfNulls<Bitmap>(Palette.SIZE)
    private val participants = IntArray(Palette.SIZE)
    private val numbers = Array(Palette.SIZE + 1) { it.toString() }

    /** When the last lock fired: a white ring expands from the face for [FLASH_MS]. */
    private var lockFlashAtMs = -FLASH_MS
    private var lockX = 0f
    private var lockY = 0f
    private var lockR = 0f

    // Emitted-state bookkeeping for the transition detection.
    private var countdownTicksSent = 0
    private var elimTicksSent = 0
    private var heavyFired = false
    private var resultHoldElapsed = false
    private var hint = -1
    private var hintCount = -1
    private var chromeVisible = true
    private var analyzerWanted = true

    // Strip geometry, rebuilt by layoutStrip (never in onDraw).
    private val thumbMax = THUMB_MAX_DP * density
    private val thumbGap = THUMB_GAP_DP * density
    private val stripMargin = STRIP_MARGIN_DP * density
    private val stripBottomBase = STRIP_BOTTOM_DP * density
    private var stripBottom = stripBottomBase
    private val thumbLeft = FloatArray(Palette.SIZE)
    private var thumbSize = thumbMax
    private var stripCy = 0f
    private var bigSize = 0f

    // Dimensions in pixels.
    private val ringStroke = RING_STROKE_DP * density
    private val glowStroke = GLOW_STROKE_DP * density
    private val thinStroke = THIN_STROKE_DP * density
    private val arcStroke = ARC_STROKE_DP * density
    private val arcOffset = ARC_OFFSET_DP * density
    private val borderStroke = BORDER_STROKE_DP * density
    private val cornerRadius = CORNER_DP * density
    private val badgeRadius = BADGE_DIAMETER_DP * density * 0.5f
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
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = borderStroke }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        color = Palette.BACKGROUND
    }
    private val arcRect = RectF()
    private val rect = RectF()
    private val clipPath = Path()

    private val sleepRunnable = Runnable { enterSleep() }
    private val idleRunnable = Runnable { checkIdle() }
    private val holdRunnable = Runnable {
        resultHoldElapsed = true
        syncDerived(SystemClock.uptimeMillis())
    }

    // ---- Configuration -----------------------------------------------------------------

    /**
     * Applies the settings snapshot; call from `onResume`. The engine is rebuilt (its timings
     * are immutable) and whatever the old one was doing is dropped; the roster is kept, a trip
     * to Settings must not cost the group its portraits.
     */
    fun configure(timings: Timings, haptics: Haptics, mode: Mode) {
        this.timings = timings
        this.haptics = haptics
        this.mode = mode
        engine = ChooserEngine(timings, random, abortOnLift = false)
        rollCall.retime(lockMs(timings), timings.collapseMs)
        paused = false
        // The host may have blanked the hint meanwhile (permission card): re-emit it.
        hint = -1
        hintCount = -1
        val now = SystemClock.uptimeMillis()
        nowMs = now
        reset(now)
        host?.onRosterChanged(rollCall.count, rollCall.canDraw())
        noteActivity(now)
    }

    /** `onPause`: nothing may run or start while the screen is not shown; the roster stays. */
    fun resetAll() {
        paused = true
        reset(SystemClock.uptimeMillis())
        clock.stop()
    }

    private fun reset(now: Long) {
        removeCallbacks(holdRunnable)
        removeCallbacks(sleepRunnable)
        removeCallbacks(idleRunnable)
        idleCheckPosted = false
        engine.abort(now)
        countdownTicksSent = 0
        elimTicksSent = 0
        heavyFired = false
        resultHoldElapsed = false
        lockFlashAtMs = -FLASH_MS
        frame.clear()
        sleeping = false
        clock.stop()
        syncDerived(now)
        invalidate()
    }

    // ---- Frames ------------------------------------------------------------------------

    /** A new face frame from the source (UI thread); only the enrolment phase reads it. */
    fun onFrameArrived(frame: FaceFrame) {
        this.frame = frame
        if (paused || sleeping) return
        val now = SystemClock.uptimeMillis()
        nowMs = now
        if (engine.phase == ChooserEngine.Phase.IDLE) {
            if (frame.confirmedCount > 0) noteActivity(now)
            if (rollCall.update(frame.confirmedCount, frame.setVersion, loneMlId(frame), now)) lockFace(now)
            syncDerived(now)
            updateLoop(now)
            invalidate()
        }
    }

    /** The tracking id of the single confirmed face, or -1 when there is none or several. */
    private fun loneMlId(f: FaceFrame): Int {
        if (f.confirmedCount != 1) return -1
        for (i in 0 until f.count) if (f.confirmed[i]) return f.mlId[i]
        return -1
    }

    override fun onFrame(frameTimeMs: Long) {
        nowMs = frameTimeMs
        tick(frameTimeMs)
    }

    /** The lone confirmed face held still long enough: take its portrait, add the player. */
    private fun lockFace(now: Long) {
        val f = frame
        var i = 0
        while (i < f.count && !f.confirmed[i]) i++
        if (i >= f.count) return
        val cx = f.cx[i]
        val cy = f.cy[i]
        val r = FaceGeometry.clampRadius(f.radius[i], minRadius, maxRadius)
        val slot = rollCall.enrol()
        if (slot < 0) return
        portraits[slot]?.recycle()
        portraits[slot] = host?.capturePortrait(cx, cy, r, THUMB_PX)
        lockX = cx
        lockY = cy
        lockR = r
        lockFlashAtMs = now
        haptics.confirm(this)
        host?.onInteraction()
        layoutStrip()
        host?.onRosterChanged(rollCall.count, rollCall.canDraw())
    }

    // ---- Round -------------------------------------------------------------------------

    /** The Go button: draws among the enrolled portraits. */
    fun startRound() {
        if (paused || sleeping || engine.phase != ChooserEngine.Phase.IDLE || !rollCall.canDraw()) return
        val now = SystemClock.uptimeMillis()
        nowMs = now
        val n = rollCall.count
        for (i in 0 until n) participants[i] = i
        if (!engine.start(participants, n, mode, now)) return
        host?.onInteraction()
        haptics.confirm(this)
        countdownTicksSent = 1
        elimTicksSent = 0
        heavyFired = false
        resultHoldElapsed = false
        removeCallbacks(idleRunnable)
        idleCheckPosted = false
        tick(now)
        invalidate()
    }

    /** The "new group" button: forgets every portrait. */
    fun newGroup() {
        if (paused || engine.phase != ChooserEngine.Phase.IDLE) return
        rollCall.clear()
        for (i in portraits.indices) {
            portraits[i]?.recycle()
            portraits[i] = null
        }
        haptics.tick(this)
        layoutStrip()
        host?.onRosterChanged(0, false)
        syncDerived(SystemClock.uptimeMillis())
        invalidate()
    }

    private fun tick(now: Long) {
        if (paused) return
        engine.tick(now)
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
                var passed = 0
                for (k in 0 until engine.eliminationCount) {
                    if (now >= engine.eliminatedAtMs(engine.eliminationSlotAt(k))) passed++
                }
                while (elimTicksSent < passed) {
                    haptics.tick(this)
                    elimTicksSent++
                }
            }
            ChooserEngine.Phase.RESULT -> if (!heavyFired) {
                heavyFired = true
                haptics.heavy()
                removeCallbacks(holdRunnable)
                postDelayed(holdRunnable, timings.resultHoldMs)
                removeCallbacks(sleepRunnable)
                postDelayed(sleepRunnable, timings.resultIdleTimeoutMs)
            }
            else -> Unit
        }
    }

    // ---- Touch -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> tap(event.x, event.y)
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Wakes a sleeping camera, removes a tapped portrait, or re-arms a finished draw. */
    fun tap(x: Float, y: Float) {
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
        when (engine.phase) {
            ChooserEngine.Phase.IDLE -> {
                val k = thumbAt(x, y)
                if (k >= 0) removePlayer(k)
            }
            ChooserEngine.Phase.RESULT -> if (engine.requestRearm(true, now)) {
                removeCallbacks(sleepRunnable)
                haptics.tick(this)
                tick(now)
                invalidate()
            }
            else -> Unit
        }
    }

    private fun thumbAt(x: Float, y: Float): Int {
        if (y < stripCy - thumbSize * 0.5f || y > stripCy + thumbSize * 0.5f) return -1
        for (k in 0 until rollCall.count) {
            if (x >= thumbLeft[k] && x <= thumbLeft[k] + thumbSize) return k
        }
        return -1
    }

    private fun removePlayer(index: Int) {
        val n = rollCall.count
        if (!rollCall.remove(index)) return
        portraits[index]?.recycle()
        for (k in index until n - 1) portraits[k] = portraits[k + 1]
        portraits[n - 1] = null
        haptics.tick(this)
        layoutStrip()
        host?.onRosterChanged(rollCall.count, rollCall.canDraw())
        syncDerived(nowMs)
        invalidate()
    }

    // ---- Idle sleep --------------------------------------------------------------------

    private fun noteActivity(now: Long) {
        idleSleep.activity(now)
        if (!idleCheckPosted && !paused && !sleeping) {
            idleCheckPosted = true
            postDelayed(idleRunnable, idleSleep.delayMs(now))
        }
    }

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

    /** Releases the camera; the portraits stay on screen and a tap brings the camera back. */
    private fun enterSleep() {
        val now = SystemClock.uptimeMillis()
        nowMs = now
        removeCallbacks(sleepRunnable)
        removeCallbacks(idleRunnable)
        idleCheckPosted = false
        engine.abort(now)
        frame.clear()
        clock.stop()
        sleeping = true
        host?.onCameraSleep()
        syncDerived(now)
        invalidate()
    }

    // ---- Derived state -----------------------------------------------------------------

    private fun syncDerived(now: Long) {
        val h = host ?: return
        val phase = engine.phase
        val wantHint: Int
        var count = rollCall.count
        if (sleeping) {
            wantHint = HINT_SLEEPING
        } else {
            wantHint = when (phase) {
                ChooserEngine.Phase.IDLE -> when (rollCall.hint()) {
                    RollCall.Hint.FIRST -> HINT_FIRST
                    RollCall.Hint.WAITING -> if (rollCall.canDraw()) HINT_READY else HINT_WAITING
                    RollCall.Hint.HOLD -> HINT_HOLD
                    RollCall.Hint.ONE_AT_A_TIME -> HINT_ONE_AT_A_TIME
                    RollCall.Hint.NEXT -> HINT_NEXT
                    else -> HINT_FULL
                }
                ChooserEngine.Phase.RESULT -> if (resultHoldElapsed) HINT_RESULT else HINT_NONE
                else -> HINT_NONE
            }
        }
        if (wantHint != HINT_READY) count = -1
        if (wantHint != hint || count != hintCount) {
            hint = wantHint
            hintCount = count
            h.onHintChanged(wantHint, rollCall.count)
        }
        // Chrome stays while sleeping so finger mode and settings remain one tap away.
        val wantChrome = phase == ChooserEngine.Phase.IDLE
        if (wantChrome != chromeVisible) {
            chromeVisible = wantChrome
            h.onChromeVisible(wantChrome)
        }
        val wantAnalyzer = phase == ChooserEngine.Phase.IDLE && !sleeping
        if (wantAnalyzer != analyzerWanted) {
            analyzerWanted = wantAnalyzer
            h.onAnalyzerEnabled(wantAnalyzer)
            if (wantAnalyzer) noteActivity(now)
        }
    }

    private fun updateLoop(now: Long) {
        if (needsFrames(now)) clock.start() else clock.stop()
    }

    private fun needsFrames(now: Long): Boolean {
        if (sleeping || paused) return false
        return when (engine.phase) {
            ChooserEngine.Phase.IDLE -> rollCall.isAnimating(now) || now - lockFlashAtMs < FLASH_MS
            ChooserEngine.Phase.RESULT ->
                engine.needsFrames(now) || now - engine.resultStartMs < WINNER_PULSE_PERIOD_MS * WINNER_PULSES
            else -> true
        }
    }

    // ---- Geometry ----------------------------------------------------------------------

    /** The navigation bar inset: the strip sits above the chrome row, which sits above the bar. */
    fun setBottomInset(px: Int) {
        val next = stripBottomBase + px
        if (next == stripBottom) return
        stripBottom = next
        layoutStrip()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = if (w < h) w else h
        maxRadius = side * MAX_RADIUS_FRACTION
        if (maxRadius < minRadius) maxRadius = minRadius
        bigSize = side * BIG_FRACTION
        layoutStrip()
    }

    /** Portraits centred at the bottom, shrinking so sixteen still fit on one row. */
    private fun layoutStrip() {
        val n = rollCall.count
        val avail = width - 2f * stripMargin
        var size = thumbMax
        if (n > 0) {
            val fit = (avail - (n - 1) * thumbGap) / n
            if (fit < size) size = fit
        }
        thumbSize = size
        val total = n * size + (n - 1) * thumbGap
        val left0 = (width - total) * 0.5f
        for (k in 0 until n) thumbLeft[k] = left0 + k * (size + thumbGap)
        stripCy = height - stripBottom - size * 0.5f
    }

    override fun onDetachedFromWindow() {
        clock.stop()
        removeCallbacks(sleepRunnable)
        removeCallbacks(holdRunnable)
        removeCallbacks(idleRunnable)
        idleCheckPosted = false
        super.onDetachedFromWindow()
    }

    // ---- Drawing -----------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (paused) return
        val now = nowMs
        when (engine.phase) {
            ChooserEngine.Phase.IDLE -> {
                if (!sleeping) drawLive(canvas, now)
                drawStrip(canvas, now, 1f)
            }
            ChooserEngine.Phase.COUNTDOWN, ChooserEngine.Phase.ELIMINATING -> drawStrip(canvas, now, 1f)
            ChooserEngine.Phase.RESULT -> drawResult(canvas, now, 1f)
            ChooserEngine.Phase.FADING -> {
                val t = Easing.progress(engine.phaseElapsedMs(now), timings.rearmFadeMs)
                drawResult(canvas, now, 1f - t)
                drawStrip(canvas, now, t)
            }
        }
    }

    /** The face in front of the camera: its ring in the next player's color, the lock arc. */
    private fun drawLive(canvas: Canvas, now: Long) {
        val f = frame
        val arc = rollCall.arc(now)
        val nextColor = Palette.of(rollCall.count)
        val lockable = f.confirmedCount == 1 && !rollCall.awaitingClear && !rollCall.isFull()
        for (i in 0 until f.count) {
            val r = FaceGeometry.clampRadius(f.radius[i], minRadius, maxRadius)
            if (f.confirmed[i] && lockable) {
                val alpha = f.alpha[i]
                glowPaint.color = Palette.withAlpha(nextColor, GLOW_ALPHA * alpha)
                canvas.drawCircle(f.cx[i], f.cy[i], r, glowPaint)
                ringPaint.color = Palette.withAlpha(nextColor, alpha)
                canvas.drawCircle(f.cx[i], f.cy[i], r, ringPaint)
                if (arc > 0f) {
                    val ar = r + arcOffset
                    arcRect.set(f.cx[i] - ar, f.cy[i] - ar, f.cx[i] + ar, f.cy[i] + ar)
                    arcPaint.color = Palette.withAlpha(Color.WHITE, alpha)
                    canvas.drawArc(arcRect, -90f, 360f * arc, false, arcPaint)
                }
            } else {
                thinPaint.color = Palette.withAlpha(Color.WHITE, THIN_ALPHA * f.alpha[i])
                canvas.drawCircle(f.cx[i], f.cy[i], r, thinPaint)
            }
        }
        val flashElapsed = now - lockFlashAtMs
        if (flashElapsed in 0 until FLASH_MS) {
            val t = Easing.easeOutCubic(Easing.progress(flashElapsed, FLASH_MS))
            thinPaint.color = Palette.withAlpha(Color.WHITE, 1f - t)
            canvas.drawCircle(lockX, lockY, lockR * (1f + t), thinPaint)
        }
    }

    /** The row of portraits, animated per phase: pulse and dimming, then eliminations. */
    private fun drawStrip(canvas: Canvas, now: Long, alpha: Float) {
        val n = rollCall.count
        if (n == 0 || alpha <= 0f) return
        val phase = engine.phase
        val half = thumbSize * 0.5f
        for (k in 0 until n) {
            var scale = 1f
            var a = alpha
            when (phase) {
                ChooserEngine.Phase.COUNTDOWN -> {
                    scale = 1f + PULSE_SCALE * Easing.pulse(engine.phaseElapsedMs(now), engine.countdownPulsePeriodMs(now))
                    val dimAt = engine.dimAtMs(k)
                    if (dimAt >= 0L && now >= dimAt) {
                        a *= DIM_ALPHA
                        scale *= DIM_SCALE
                    }
                }
                ChooserEngine.Phase.ELIMINATING -> {
                    val at = engine.eliminatedAtMs(k)
                    if (at >= 0L && now >= at) {
                        val t = Easing.easeInQuad(Easing.progress(now - at, timings.elimAnimMs))
                        if (t >= 1f) continue
                        scale = 1f - t
                        a *= 1f - t
                    } else if (at >= 0L) {
                        a *= DIM_ALPHA
                        scale = DIM_SCALE
                    }
                }
                else -> Unit
            }
            drawPortrait(canvas, thumbLeft[k] + half, stripCy, thumbSize * scale, k, a)
        }
    }

    /** The winner(s) grow from the strip to the centre while their color floods the view. */
    private fun drawResult(canvas: Canvas, now: Long, fade: Float) {
        val elapsed = now - engine.resultStartMs
        val flood = Easing.easeOutCubic(Easing.progress(elapsed, timings.floodMs))
        val w = width.toFloat()
        val h = height.toFloat()
        val winners = engine.winnerCount
        if (winners <= 0) return
        val columnWidth = w / winners
        var k = 0
        for (s in 0 until Palette.SIZE) {
            if (!engine.isWinner(s)) continue
            val color = Palette.of(s)
            val left = k * columnWidth
            fillPaint.color = Palette.withAlpha(color, TINT_ALPHA * flood * fade)
            canvas.drawRect(left, 0f, left + columnWidth, h, fillPaint)
            fillPaint.color = Palette.withAlpha(color, BAND_ALPHA * fade)
            canvas.drawRect(left, h - bandHeight * flood, left + columnWidth, h, fillPaint)
            k++
        }
        val pulse = if (elapsed < WINNER_PULSE_PERIOD_MS * WINNER_PULSES) {
            Easing.pulse(elapsed, WINNER_PULSE_PERIOD_MS)
        } else {
            0f
        }
        var size = bigSize
        if (winners > 1) {
            val fit = (w - (winners + 1) * stripMargin) / winners
            if (fit < size) size = fit
        }
        k = 0
        for (s in 0 until Palette.SIZE) {
            if (!engine.isWinner(s)) continue
            val targetX = columnWidth * (k + 0.5f)
            val targetY = h * WINNER_Y_FRACTION
            val fromX = thumbLeft[s] + thumbSize * 0.5f
            val cx = Easing.lerp(fromX, targetX, flood)
            val cy = Easing.lerp(stripCy, targetY, flood)
            val sz = Easing.lerp(thumbSize, size, flood)
            val color = Palette.of(s)
            glowPaint.color = Palette.withAlpha(color, (GLOW_ALPHA + 0.3f * pulse) * fade)
            rect.set(cx - sz * 0.5f - glowStroke, cy - sz * 0.5f - glowStroke, cx + sz * 0.5f + glowStroke, cy + sz * 0.5f + glowStroke)
            canvas.drawRoundRect(rect, cornerRadius + glowStroke, cornerRadius + glowStroke, glowPaint)
            drawPortrait(canvas, cx, cy, sz, s, fade)
            k++
        }
    }

    /** One portrait tile: the bitmap clipped to a rounded square, its color border, its number. */
    private fun drawPortrait(canvas: Canvas, cx: Float, cy: Float, size: Float, slot: Int, alpha: Float) {
        if (size <= 0f || alpha <= 0f) return
        val color = Palette.of(slot)
        val half = size * 0.5f
        rect.set(cx - half, cy - half, cx + half, cy + half)
        val radius = cornerRadius * (size / thumbMax).coerceAtMost(2f)
        val bitmap = portraits[slot]
        if (bitmap != null && !bitmap.isRecycled) {
            clipPath.reset()
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            bitmapPaint.alpha = (alpha * 255f + 0.5f).toInt()
            canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
            canvas.restore()
        } else {
            fillPaint.color = Palette.withAlpha(color, TILE_ALPHA * alpha)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
        borderPaint.color = Palette.withAlpha(color, alpha)
        borderPaint.strokeWidth = borderStroke * (size / thumbMax).coerceIn(0.5f, 2f)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
        // Number badge on the bottom-right corner, small enough to leave the face visible.
        val br = badgeRadius * (size / thumbMax).coerceIn(0.6f, 2f)
        val bx = rect.right - br * 0.4f
        val by = rect.bottom - br * 0.4f
        badgePaint.color = Palette.withAlpha(color, alpha)
        canvas.drawCircle(bx, by, br, badgePaint)
        textPaint.textSize = br * 1.2f
        textPaint.alpha = (alpha * 255f + 0.5f).toInt()
        canvas.drawText(numbers[slot + 1], bx, by + br * 0.42f, textPaint)
    }

    companion object {
        const val HINT_NONE = 0
        const val HINT_FIRST = 1
        const val HINT_WAITING = 2
        const val HINT_HOLD = 3
        const val HINT_ONE_AT_A_TIME = 4
        const val HINT_NEXT = 5
        const val HINT_READY = 6
        const val HINT_FULL = 7
        const val HINT_RESULT = 8
        const val HINT_SLEEPING = 9

        /** Portrait bitmap side; square, scaled by the capture. */
        const val THUMB_PX = 256

        /** One person alone locks faster than a group settles: the finger delay, not the camera one. */
        fun lockMs(timings: Timings): Long {
            val ms = timings.stableMs - Timings.CAMERA_EXTRA_STABLE_MS
            return if (ms < MIN_LOCK_MS) MIN_LOCK_MS else ms
        }

        private const val MIN_LOCK_MS = 800L
        /** The frame must be empty this long after a lock before the next player can be timed. */
        private const val CLEAR_MS = 600L
        private const val FLASH_MS = 350L
        private const val IDLE_SLEEP_MS = 3L * 60L * 1000L

        private const val THUMB_MAX_DP = 72f
        private const val THUMB_GAP_DP = 8f
        private const val STRIP_MARGIN_DP = 16f
        /** Space left under the strip for the bottom chrome row. */
        private const val STRIP_BOTTOM_DP = 104f
        private const val BIG_FRACTION = 0.62f
        private const val WINNER_Y_FRACTION = 0.42f

        private const val RING_STROKE_DP = 4f
        private const val GLOW_STROKE_DP = 32f
        private const val THIN_STROKE_DP = 1.5f
        private const val ARC_STROKE_DP = 2.5f
        private const val ARC_OFFSET_DP = 8f
        private const val BORDER_STROKE_DP = 2.5f
        private const val CORNER_DP = 16f
        private const val BADGE_DIAMETER_DP = 20f
        private const val BAND_HEIGHT_DP = 72f
        private const val MIN_RADIUS_DP = 28f
        private const val MAX_RADIUS_FRACTION = 0.45f

        private const val GLOW_ALPHA = 0.15f
        private const val THIN_ALPHA = 0.5f
        private const val TILE_ALPHA = 0.35f
        private const val TINT_ALPHA = 0.35f
        private const val BAND_ALPHA = 0.9f
        private const val PULSE_SCALE = 0.12f
        private const val DIM_ALPHA = 0.55f
        private const val DIM_SCALE = 0.92f
        private const val WINNER_PULSE_PERIOD_MS = 600L
        private const val WINNER_PULSES = 3L
    }
}
