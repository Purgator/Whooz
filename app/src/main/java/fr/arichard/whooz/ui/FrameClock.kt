package fr.arichard.whooz.ui

import android.view.Choreographer
import android.view.View

/**
 * One vsync-driven loop per custom view. While running, every frame hands the frame time to
 * the [listener] and then invalidates the [view], so animations are pure functions of time and
 * nothing is drawn while nothing animates (the owner starts and stops the clock on demand).
 *
 * The [Choreographer.FrameCallback] is a single field: posting it again each frame allocates
 * nothing, which keeps the 60 fps path free of garbage-collector pauses.
 */
class FrameClock(private val view: View, private val listener: Listener) {

    /** Receives the frame time in milliseconds (same clock as `SystemClock.uptimeMillis`). */
    interface Listener {
        fun onFrame(frameTimeMs: Long)
    }

    /** True between [start] and [stop]. Read by views to decide whether a touch must invalidate. */
    var isRunning: Boolean = false
        private set

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // A stop() issued from inside onFrame must win over the re-post below.
            if (!isRunning) return
            listener.onFrame(frameTimeNanos / 1_000_000L)
            view.invalidate()
            if (isRunning) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Starts the loop; a running clock ignores the call so the callback is never posted twice. */
    fun start() {
        if (isRunning) return
        isRunning = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /** Stops the loop and drops the pending callback so no frame is drawn after this returns. */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }
}
