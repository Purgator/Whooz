package fr.arichard.whooz.ui

import android.view.View

/**
 * The buttons, pill and hints layered over an arena. They are shown only while the arena is
 * idle with no finger down: a landing finger must never press a button, and a stray finger
 * must never flip the sub-mode mid-countdown. Hiding therefore drops `isClickable` and
 * `isFocusable` at once, while the alpha fades over [FADE_MS] with `View.animate()` so the
 * custom view's own frame loop stays uninvolved.
 */
class Chrome(private val views: List<View>) {

    /** Current logical state; the alpha may still be mid-fade. */
    var isVisible: Boolean = true
        private set

    /**
     * Shows or hides every chrome view. Idempotent: the arena calls this on every touch change,
     * so an unchanged state must not restart the fade. A flip cancels the pending animation so
     * a quick lift-and-land never leaves a button half visible.
     */
    fun setVisible(visible: Boolean) {
        if (isVisible == visible) return
        isVisible = visible
        val alpha = if (visible) 1f else 0f
        for (i in views.indices) {
            val v = views[i]
            v.animate().cancel()
            v.isClickable = visible
            v.isFocusable = visible
            v.animate().alpha(alpha).setDuration(FADE_MS).start()
        }
    }

    /**
     * Applies the state without animating, for `onCreate`/`onResume` where the arena is already
     * idle and a fade-in would flicker, or when a view must vanish on the same frame.
     */
    fun setVisibleNow(visible: Boolean) {
        isVisible = visible
        val alpha = if (visible) 1f else 0f
        for (i in views.indices) {
            val v = views[i]
            v.animate().cancel()
            v.isClickable = visible
            v.isFocusable = visible
            v.alpha = alpha
        }
    }

    companion object {
        /** Fade length; short enough that the chrome is back before the players look for it. */
        const val FADE_MS = 300L
    }
}
