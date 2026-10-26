package fr.arichard.whooz.camera

import fr.arichard.whooz.core.Timings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceTrackerTest {
    /** Camera timings of the NORMAL preset: graceMs 700, rescueMs 500. */
    private val timings = Timings(stableMs = 1800, resultHoldMs = 4000)
    private val tracker = FaceTracker(timings)
    private val obs = FaceObservations()
    private val frame = FaceFrame()
    private var now = 1_000L

    /** One frame with the given faces, 66 ms after the previous one (the analysis throttle). */
    private fun frame(vararg faces: FaceObservation): FaceFrame {
        now += 66
        return frameAt(now, *faces)
    }

    private fun frameAt(t: Long, vararg faces: FaceObservation): FaceFrame {
        now = t
        obs.clear()
        for (f in faces) obs.add(f)
        tracker.update(obs, t)
        tracker.writeTo(frame)
        return frame
    }

    /** Three hits so the face is CONFIRMED; returns the frame after the third. */
    private fun confirm(vararg faces: FaceObservation): FaceFrame {
        frame(*faces)
        frame(*faces)
        return frame(*faces)
    }

    private fun face(id: Int, cx: Float, cy: Float, r: Float = 100f) = FaceObservation(id, cx, cy, r)

    @Test
    fun sameMlIdKeepsPlayerIdEvenWhenFar() {
        confirm(face(7, 100f, 100f))
        val f = frame(face(7, 900f, 900f))
        assertEquals(1, f.count)
        assertEquals(0, f.slot[0])
        assertEquals(1, f.confirmedCount)
    }

    @Test
    fun idChangeNearbyReattachesToSameSlot() {
        confirm(face(7, 100f, 100f))
        val before = tracker.setVersion
        // New id, centre 30 px away (limit 60), same radius.
        val f = frame(face(8, 130f, 100f))
        assertEquals(1, f.count)
        assertEquals(0, f.slot[0])
        assertTrue(f.confirmed[0])
        assertEquals(8, tracker.mlIdOf(0))
        assertEquals("rescue must not bump the version", before, tracker.setVersion)
    }

    @Test
    fun idChangeWithinRescueWindowReattaches() {
        confirm(face(7, 100f, 100f))
        // Missing for one frame (66 ms < rescueMs 500), then back under a new id nearby.
        frame()
        assertTrue(tracker.isMissing(0))
        val f = frame(face(8, 120f, 110f))
        assertEquals(1, f.count)
        assertEquals(0, f.slot[0])
        assertFalse(tracker.isMissing(0))
        assertEquals(1f, f.alpha[0], EPS)
    }

    @Test
    fun idChangeAfterRescueWindowSpawnsNewSlot() {
        confirm(face(7, 100f, 100f))
        frameAt(now + 66)
        // 600 ms missing: past rescueMs (500) but inside graceMs (700), the old ring still fades.
        val f = frameAt(now + 600, face(8, 120f, 110f))
        assertEquals(2, f.count)
        assertEquals(0, f.slot[0])
        assertEquals(1, f.slot[1])
        assertTrue(f.confirmed[0])
        assertFalse(f.confirmed[1])
    }

    @Test
    fun farNewFaceGetsNewSlot() {
        confirm(face(7, 100f, 100f))
        val f = frame(face(7, 100f, 100f), face(-1, 500f, 500f))
        assertEquals(2, f.count)
        assertEquals(0, f.slot[0])
        assertEquals(1, f.slot[1])
    }

    @Test
    fun radiusRatioOutsideBoundsIsNotAMatch() {
        confirm(face(7, 100f, 100f, 100f))
        // Same spot, but twice the size: not the same face, a new slot.
        var f = frame(face(8, 100f, 100f, 200f))
        assertEquals(2, f.count)
        // Just inside the bounds on both sides (0.6 and 1.6 of the track radius): a match.
        tracker.clear()
        confirm(face(7, 100f, 100f, 100f))
        f = frame(face(9, 100f, 100f, 61f))
        assertEquals(1, f.count)
        tracker.clear()
        confirm(face(7, 100f, 100f, 100f))
        f = frame(face(9, 100f, 100f, 159f))
        assertEquals(1, f.count)
        // Just outside: a new slot.
        tracker.clear()
        confirm(face(7, 100f, 100f, 100f))
        f = frame(face(9, 100f, 100f, 50f))
        assertEquals(2, f.count)
    }

    @Test
    fun threeHitsToConfirmed() {
        var f = frame(face(1, 100f, 100f))
        assertEquals(1, f.count)
        assertFalse(f.confirmed[0])
        assertEquals(0, f.confirmedCount)
        f = frame(face(1, 100f, 100f))
        assertFalse(f.confirmed[0])
        f = frame(face(1, 100f, 100f))
        assertTrue(f.confirmed[0])
        assertEquals(1, f.confirmedCount)
        assertEquals(1, tracker.confirmedCount)
    }

    @Test
    fun tentativeMissingOneFrameIsDropped() {
        frame(face(1, 100f, 100f))
        frame(face(1, 100f, 100f))
        val version = tracker.setVersion
        var f = frame()
        assertEquals(0, f.count)
        assertEquals("dropping a tentative face is not a set change", version, tracker.setVersion)
        // Coming back starts the count over.
        f = frame(face(1, 100f, 100f))
        assertFalse(f.confirmed[0])
    }

    @Test
    fun gapKeepsConfirmedTrackWithDecreasingAlpha() {
        confirm(face(1, 100f, 100f))
        val t0 = now
        var f = frameAt(t0 + 66)
        assertEquals(1, f.count)
        assertTrue(f.confirmed[0])
        assertEquals(1f, f.alpha[0], EPS)
        val a1 = f.alpha[0]
        f = frameAt(t0 + 300)
        val a2 = f.alpha[0]
        f = frameAt(t0 + 500)
        val a3 = f.alpha[0]
        assertEquals(1, f.count)
        assertTrue(a2 < a1)
        assertTrue(a3 < a2)
        assertTrue(a3 >= 0.4f)
        // Position is frozen at the last smoothed value.
        assertEquals(100f, f.cx[0], EPS)
        assertEquals(1, f.confirmedCount)
    }

    @Test
    fun goneAfterGraceFreesSlotAndBumpsVersion() {
        confirm(face(1, 100f, 100f))
        val version = tracker.setVersion
        val t0 = now
        frameAt(t0 + 66)
        var f = frameAt(t0 + 66 + 699)
        assertEquals(1, f.count)
        assertEquals(version, tracker.setVersion)
        f = frameAt(t0 + 66 + 700)
        assertEquals(0, f.count)
        assertEquals(0, f.confirmedCount)
        assertEquals(version + 1, tracker.setVersion)
        assertFalse(tracker.isTracked(0))
    }

    @Test
    fun lowestFreeSlotIsReused() {
        confirm(face(1, 100f, 100f), face(2, 400f, 100f), face(3, 700f, 100f))
        // Player 1 (slot 1) leaves for good.
        val t0 = now
        frameAt(t0 + 66, face(1, 100f, 100f), face(3, 700f, 100f))
        var f = frameAt(t0 + 66 + 700, face(1, 100f, 100f), face(3, 700f, 100f))
        assertEquals(2, f.count)
        assertEquals(0, f.slot[0])
        assertEquals(2, f.slot[1])
        // A new face takes slot 1, not slot 3.
        f = frame(face(1, 100f, 100f), face(3, 700f, 100f), face(4, 400f, 400f))
        assertEquals(3, f.count)
        assertEquals(1, f.slot[1])
        assertEquals(4, tracker.mlIdOf(1))
    }

    @Test
    fun smoothingIsSkippedOnFirstHitAndConverges() {
        var f = frame(face(1, 100f, 100f, 80f))
        assertEquals(100f, f.cx[0], EPS)
        assertEquals(100f, f.cy[0], EPS)
        assertEquals(80f, f.radius[0], EPS)
        // Second hit moves 35 % of the way.
        f = frame(face(1, 200f, 100f, 100f))
        assertEquals(135f, f.cx[0], EPS)
        assertEquals(100f, f.cy[0], EPS)
        assertEquals(87f, f.radius[0], EPS)
        // Convergence: after many frames the ring sits on the face.
        for (i in 0 until 40) f = frame(face(1, 200f, 100f, 100f))
        assertEquals(200f, f.cx[0], 0.01f)
        assertEquals(100f, f.radius[0], 0.01f)
    }

    @Test
    fun swappingFacesKeepIdsViaMlIdPriority() {
        confirm(face(1, 100f, 100f), face(2, 400f, 100f))
        // Positions swap in one frame but the ids follow the faces.
        val f = frame(face(2, 100f, 100f), face(1, 400f, 100f))
        assertEquals(2, f.count)
        assertEquals(1, tracker.mlIdOf(0))
        assertEquals(2, tracker.mlIdOf(1))
        // Slot 0 (id 1) is now heading right, slot 1 (id 2) heading left.
        assertTrue(f.cx[0] > 100f)
        assertTrue(f.cx[1] < 400f)
    }

    @Test
    fun capacityIsSixteen() {
        val faces = Array(17) { i -> face(i + 1, 100f + i * 300f, 100f + (i % 3) * 300f) }
        assertEquals(16, FaceObservations.CAPACITY)
        obs.clear()
        var accepted = 0
        for (f in faces) if (obs.add(f)) accepted++
        assertEquals(16, accepted)
        assertEquals(16, obs.count)
        val f = frame(*faces)
        assertEquals(16, f.count)
        for (i in 0 until 16) assertEquals(i, f.slot[i])
        // Three frames in, everyone is confirmed and the count tops out at 16.
        frame(*faces)
        frame(*faces)
        assertEquals(16, tracker.confirmedCount)
    }

    @Test
    fun smallerCapacityStopsSpawning() {
        val small = FaceTracker(timings, capacity = 2)
        obs.clear()
        obs.add(face(1, 100f, 100f))
        obs.add(face(2, 400f, 100f))
        obs.add(face(3, 700f, 100f))
        small.update(obs, 1L)
        small.writeTo(frame)
        assertEquals(2, frame.count)
        assertEquals(1, small.mlIdOf(0))
        assertEquals(2, small.mlIdOf(1))
    }

    @Test
    fun outputIsSortedBySlot() {
        confirm(face(1, 100f, 100f), face(2, 400f, 100f), face(3, 700f, 100f))
        val t0 = now
        frameAt(t0 + 66, face(2, 400f, 100f), face(3, 700f, 100f))
        frameAt(t0 + 66 + 700, face(2, 400f, 100f), face(3, 700f, 100f))
        // Slot 0 is free; observations arrive in an order unrelated to slots.
        val f = frame(face(3, 700f, 100f), face(9, 100f, 400f), face(2, 400f, 100f))
        assertEquals(3, f.count)
        assertEquals(0, f.slot[0])
        assertEquals(1, f.slot[1])
        assertEquals(2, f.slot[2])
        assertEquals(9, tracker.mlIdOf(0))
        assertEquals(2, tracker.mlIdOf(1))
        assertEquals(3, tracker.mlIdOf(2))
    }

    @Test
    fun setVersionBumpsOnlyOnConfirmAndGone() {
        assertEquals(0, tracker.setVersion)
        frame(face(1, 100f, 100f))
        assertEquals("spawn", 0, tracker.setVersion)
        frame(face(1, 110f, 100f))
        assertEquals("second hit", 0, tracker.setVersion)
        frame(face(1, 120f, 100f))
        assertEquals("confirm", 1, tracker.setVersion)
        frame(face(1, 130f, 100f))
        assertEquals("move", 1, tracker.setVersion)
        // New id a few pixels from the smoothed ring: a geometric rescue, same slot.
        frame(face(5, 135f, 100f))
        assertEquals("rescue", 1, tracker.setVersion)
        assertEquals(5, tracker.mlIdOf(0))
        frame(face(5, 135f, 100f), face(6, 800f, 800f))
        assertEquals("tentative spawn", 1, tracker.setVersion)
        frame(face(5, 135f, 100f))
        assertEquals("tentative drop", 1, tracker.setVersion)
        val t0 = now
        frameAt(t0 + 66)
        assertEquals("first miss", 1, tracker.setVersion)
        frameAt(t0 + 400)
        assertEquals("fading", 1, tracker.setVersion)
        frameAt(t0 + 66 + 700)
        assertEquals("gone", 2, tracker.setVersion)
    }

    @Test
    fun clearForgetsEverythingAndBumpsOnlyWhenConfirmedExisted() {
        frame(face(1, 100f, 100f))
        tracker.clear()
        assertEquals(0, tracker.setVersion)
        confirm(face(1, 100f, 100f))
        assertEquals(1, tracker.setVersion)
        tracker.clear()
        assertEquals(2, tracker.setVersion)
        assertEquals(0, tracker.confirmedCount)
        tracker.writeTo(frame)
        assertEquals(0, frame.count)
        // Slot 0 is free again.
        val f = frame(face(2, 500f, 500f))
        assertEquals(0, f.slot[0])
    }

    @Test
    fun observationsWithoutIdTrackByGeometry() {
        // Tracking disabled or lost: every observation says -1, geometry alone keeps the slot.
        confirm(face(-1, 100f, 100f))
        val f = frame(face(-1, 120f, 105f))
        assertEquals(1, f.count)
        assertTrue(f.confirmed[0])
        assertEquals(-1, tracker.mlIdOf(0))
    }

    @Test
    fun writeToLeavesFrameGeometryAlone() {
        frame.bufferW = 640
        frame.bufferH = 480
        frame.rotation = 270
        frame(face(1, 100f, 100f))
        assertEquals(640, frame.bufferW)
        assertEquals(480, frame.bufferH)
        assertEquals(270, frame.rotation)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
