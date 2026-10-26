package fr.arichard.whooz.core

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Allocation-free animation math. Every function is total: no input ever throws. */
object Easing {
    fun clamp01(t: Float): Float = if (t < 0f) 0f else if (t > 1f) 1f else t

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * clamp01(t)

    /** Fast start, soft landing. */
    fun easeOutCubic(t: Float): Float {
        val x = 1f - clamp01(t)
        return 1f - x * x * x
    }

    /** Slow start, hard landing. */
    fun easeInQuad(t: Float): Float {
        val x = clamp01(t)
        return x * x
    }

    /** Overshoot pop: 0 → ~1.08 → 1. */
    fun pop(t: Float): Float {
        val x = clamp01(t)
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val y = x - 1f
        return 1f + c3 * y * y * y + c1 * y * y
    }

    /** Sine pulse in [0, 1] with the given period; phase 0 at [elapsedMs] = 0. */
    fun pulse(elapsedMs: Long, periodMs: Long): Float {
        if (periodMs <= 0L) return 0f
        val phase = (elapsedMs.toDouble() / periodMs.toDouble()) * 2.0 * PI
        return ((1.0 - kotlin.math.cos(phase)) * 0.5).toFloat()
    }

    /** Elapsed fraction of a window, clamped to [0, 1]; a zero-length window is complete. */
    fun progress(elapsedMs: Long, durationMs: Long): Float =
        if (durationMs <= 0L) 1f else clamp01(elapsedMs.toFloat() / durationMs.toFloat())

    /** Distance from (x, y) to the farthest corner of a w × h rectangle. */
    fun farthestCorner(x: Float, y: Float, w: Float, h: Float): Float {
        val dx = max(x, w - x)
        val dy = max(y, h - y)
        return sqrt(dx * dx + dy * dy)
    }

    /** Radius of the winner flood at fraction [t] of its growth: covers the screen at t = 1. */
    fun floodRadius(x: Float, y: Float, w: Float, h: Float, t: Float): Float =
        easeOutCubic(t) * farthestCorner(x, y, w, h)

    /** Smooth 0..1 breathing value, period [periodMs]. */
    fun breathe(elapsedMs: Long, periodMs: Long): Float =
        if (periodMs <= 0L) 0f else ((sin(elapsedMs.toDouble() / periodMs * 2.0 * PI) + 1.0) * 0.5).toFloat()
}
