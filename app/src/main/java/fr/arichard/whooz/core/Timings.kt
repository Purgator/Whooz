package fr.arichard.whooz.core

/** How long everyone must hold still before a round starts. */
enum class StabilizationPreset(val fingerMs: Long) {
    FAST(1000), NORMAL(1500), SLOW(2500);

    companion object {
        fun fromName(name: String?): StabilizationPreset =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * Every duration of a round, in milliseconds. Immutable; built once per screen from the
 * settings snapshot and handed to the pure engine so tests can shorten it freely.
 */
data class Timings(
    /** Unchanged participant set required before the countdown starts. */
    val stableMs: Long,
    /** Pulsing countdown once the set is frozen. */
    val countdownMs: Long = 1000,
    /** Gap between two eliminations. */
    val elimStepMs: Long = 350,
    /** Shrink-and-fade of one eliminated circle. */
    val elimAnimMs: Long = 250,
    /** Growth of the winner flood (or bands) over the screen. */
    val floodMs: Long = 500,
    /** Minimum time a result stays before a new round can be armed. */
    val resultHoldMs: Long,
    /** Fade of the result back to the idle arena. */
    val rearmFadeMs: Long = 400,
    /** Arming arcs animating back to zero after the set changed. */
    val collapseMs: Long = 150,
    /** A face missing for less than this keeps its ring (dimmed). */
    val graceMs: Long = 700,
    /** A new ML Kit id this close in time and space inherits the old track. */
    val rescueMs: Long = 500,
    /** Untouched camera result after which the camera is released. */
    val resultIdleTimeoutMs: Long = 60_000,
) {
    companion object {
        /** Camera stabilization is a little longer: faces jitter more than fingers. */
        const val CAMERA_EXTRA_STABLE_MS = 300L

        fun finger(preset: StabilizationPreset): Timings =
            Timings(stableMs = preset.fingerMs, resultHoldMs = 2500)

        fun camera(preset: StabilizationPreset): Timings =
            Timings(stableMs = preset.fingerMs + CAMERA_EXTRA_STABLE_MS, resultHoldMs = 4000)
    }
}
