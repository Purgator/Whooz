package fr.arichard.whooz.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The three haptic cues of a round: a light tick per countdown step or finger landing, a
 * confirmation when the set is armed, and one heavy click for the reveal. Built once per
 * screen from the settings snapshot; when the user turned haptics off every call is a no-op,
 * so callers never check the setting themselves.
 *
 * Only state transitions call these methods, never `onDraw`: a vibration from the draw path
 * would fire once per frame.
 */
class Haptics(context: Context, private val enabled: Boolean) {

    // Resolved once: looking the service up on every reveal would be wasted work and the
    // manager API differs by platform version.
    private val vibrator: Vibrator? = if (!enabled) null else resolveVibrator(context)

    /**
     * Light tick: finger landed, countdown step, elimination. Routed through the view so the
     * system "touch feedback" setting is honoured.
     */
    fun tick(view: View) {
        if (!enabled) return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /**
     * Medium cue when the set is armed. CONFIRM exists from API 30; LONG_PRESS is the closest
     * older equivalent.
     */
    fun confirm(view: View) {
        if (!enabled) return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }

    /**
     * The reveal: a single heavy click. Uses the vibrator directly because no view feedback
     * constant is strong enough to be felt by a table full of players.
     */
    fun heavy() {
        val v = vibrator ?: return
        try {
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            } else {
                VibrationEffect.createOneShot(HEAVY_FALLBACK_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            v.vibrate(effect)
        } catch (_: SecurityException) {
            // Some OEM builds enforce VIBRATE per profile; losing one click is acceptable.
        } catch (_: IllegalStateException) {
            // The vibrator service can be mid-restart; same answer.
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private companion object {
        /** Length of the pre-API-29 heavy click, chosen to feel like EFFECT_HEAVY_CLICK. */
        const val HEAVY_FALLBACK_MS = 60L
    }
}
