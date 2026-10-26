package fr.arichard.whooz.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TimingsTest {
    @Test
    fun presetsMapToFingerDelays() {
        assertEquals(1000L, Timings.finger(StabilizationPreset.FAST).stableMs)
        assertEquals(1500L, Timings.finger(StabilizationPreset.NORMAL).stableMs)
        assertEquals(2500L, Timings.finger(StabilizationPreset.SLOW).stableMs)
    }

    @Test
    fun cameraAddsThreeHundredMs() {
        assertEquals(1300L, Timings.camera(StabilizationPreset.FAST).stableMs)
        assertEquals(1800L, Timings.camera(StabilizationPreset.NORMAL).stableMs)
        assertEquals(2800L, Timings.camera(StabilizationPreset.SLOW).stableMs)
    }

    @Test
    fun holdValuesAndDefaults() {
        val f = Timings.finger(StabilizationPreset.NORMAL)
        val c = Timings.camera(StabilizationPreset.NORMAL)
        assertEquals(2500L, f.resultHoldMs)
        assertEquals(4000L, c.resultHoldMs)
        for (t in listOf(f, c)) {
            assertEquals(1000L, t.countdownMs)
            assertEquals(350L, t.elimStepMs)
            assertEquals(250L, t.elimAnimMs)
            assertEquals(500L, t.floodMs)
            assertEquals(400L, t.rearmFadeMs)
            assertEquals(150L, t.collapseMs)
            assertEquals(700L, t.graceMs)
            assertEquals(500L, t.rescueMs)
            assertEquals(60_000L, t.resultIdleTimeoutMs)
        }
    }

    @Test
    fun presetNamesFallBackToNormal() {
        assertEquals(StabilizationPreset.FAST, StabilizationPreset.fromName("FAST"))
        assertEquals(StabilizationPreset.NORMAL, StabilizationPreset.fromName(null))
        assertEquals(StabilizationPreset.NORMAL, StabilizationPreset.fromName("fast"))
        assertEquals(StabilizationPreset.NORMAL, StabilizationPreset.fromName("whatever"))
    }
}
