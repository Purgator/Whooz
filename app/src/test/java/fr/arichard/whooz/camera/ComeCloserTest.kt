package fr.arichard.whooz.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComeCloserTest {
    private val minRadius = 60f
    private val hold = 3_000L
    private val frame = FaceFrame()

    private fun setFaces(vararg faces: Pair<Float, Boolean>) {
        frame.count = faces.size
        var confirmed = 0
        for (i in faces.indices) {
            frame.slot[i] = i
            frame.radius[i] = faces[i].first
            frame.confirmed[i] = faces[i].second
            if (faces[i].second) confirmed++
        }
        frame.confirmedCount = confirmed
    }

    @Test
    fun noFacesNeverAsks() {
        val c = ComeCloser(minRadius, hold)
        setFaces()
        assertFalse(c.update(frame, 0L))
        assertFalse(c.update(frame, 10_000L))
    }

    @Test
    fun smallConfirmedFacesAskAfterTheHold() {
        val c = ComeCloser(minRadius, hold)
        setFaces(30f to true, 40f to true)
        assertFalse(c.update(frame, 1_000L))
        assertFalse(c.update(frame, 1_000L + hold - 1))
        assertTrue(c.update(frame, 1_000L + hold))
        assertTrue(c.update(frame, 1_000L + hold + 5_000L))
    }

    @Test
    fun oneLargeConfirmedFaceResetsTheTimer() {
        val c = ComeCloser(minRadius, hold)
        setFaces(30f to true)
        assertFalse(c.update(frame, 0L))
        setFaces(30f to true, 80f to true)
        assertFalse(c.update(frame, 2_000L))
        setFaces(30f to true)
        // The timer restarted at 2 500, not at 0.
        assertFalse(c.update(frame, 2_500L))
        assertFalse(c.update(frame, 4_000L))
        assertTrue(c.update(frame, 5_500L))
    }

    @Test
    fun tentativeFacesAreIgnoredOnBothSides() {
        val c = ComeCloser(minRadius, hold)
        // A far tentative face does not start the timer.
        setFaces(20f to false)
        assertFalse(c.update(frame, 0L))
        assertFalse(c.update(frame, 10_000L))
        // A large tentative face does not silence small confirmed ones.
        setFaces(20f to true, 200f to false)
        assertFalse(c.update(frame, 10_000L))
        assertTrue(c.update(frame, 13_000L))
    }

    @Test
    fun exactThresholdCountsAsLargeEnough() {
        val c = ComeCloser(minRadius, hold)
        setFaces(60f to true)
        assertFalse(c.update(frame, 0L))
        assertFalse(c.update(frame, 10_000L))
    }

    @Test
    fun losingEveryFaceClearsTheHint() {
        val c = ComeCloser(minRadius, hold)
        setFaces(20f to true)
        c.update(frame, 0L)
        assertTrue(c.update(frame, hold))
        setFaces()
        assertFalse(c.update(frame, hold + 1))
        setFaces(20f to true)
        assertFalse(c.update(frame, hold + 2))
    }

    @Test
    fun resetForgetsTheTimer() {
        val c = ComeCloser(minRadius, hold)
        setFaces(20f to true)
        c.update(frame, 0L)
        c.reset()
        assertFalse(c.update(frame, hold))
        assertTrue(c.update(frame, 2 * hold))
    }
}
