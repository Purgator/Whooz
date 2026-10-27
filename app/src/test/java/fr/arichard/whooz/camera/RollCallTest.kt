package fr.arichard.whooz.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RollCallTest {

    private fun call() = RollCall(lockMs = 1000, collapseMs = 150, clearMs = 500, capacity = 4)

    /** Feeds a constant frame from [from] to [to] every 50 ms; returns the first lock time or -1. */
    private fun hold(rc: RollCall, confirmed: Int, version: Int, from: Long, to: Long): Long {
        var t = from
        while (t <= to) {
            if (rc.update(confirmed, version, t)) return t
            t += 50
        }
        return -1L
    }

    @Test
    fun loneFaceLocksAfterLockMs() {
        val rc = call()
        assertEquals(RollCall.Hint.FIRST, rc.hint())
        val lockAt = hold(rc, 1, 1, 0, 2000)
        assertEquals(1000L, lockAt)
        assertEquals(0, rc.enrol())
        assertEquals(1, rc.count)
        assertTrue(rc.awaitingClear)
    }

    @Test
    fun twoFacesNeverLock() {
        val rc = call()
        assertEquals(-1L, hold(rc, 2, 1, 0, 3000))
        assertEquals(RollCall.Hint.ONE_AT_A_TIME, rc.hint())
    }

    @Test
    fun faceChangeRestartsTheTimer() {
        val rc = call()
        assertEquals(-1L, hold(rc, 1, 1, 0, 600))
        // A different face at 650 ms: the set version changes, the timer restarts after the collapse.
        assertEquals(-1L, hold(rc, 1, 2, 650, 1600))
        assertTrue(hold(rc, 1, 2, 1650, 3000) > 0)
    }

    @Test
    fun samePlayerStayingIsNotEnrolledTwice() {
        val rc = call()
        assertTrue(hold(rc, 1, 1, 0, 1000) >= 0)
        rc.enrol()
        // Still in front of the camera for five seconds: nothing happens, hint says next.
        assertEquals(-1L, hold(rc, 1, 1, 1050, 6000))
        assertEquals(RollCall.Hint.NEXT, rc.hint())
        assertEquals(1, rc.count)
    }

    @Test
    fun nextPlayerLocksOnlyAfterTheFrameWasEmpty() {
        val rc = call()
        assertTrue(hold(rc, 1, 1, 0, 1000) >= 0)
        rc.enrol()
        // Empty for 300 ms only, then a face: the clear window was not met, the timer does not run.
        assertEquals(-1L, hold(rc, 0, 1, 1050, 1300))
        assertTrue(rc.awaitingClear)
        assertEquals(-1L, hold(rc, 1, 2, 1350, 1500))
        // Empty for the full clear window, then the second player holds still.
        assertEquals(-1L, hold(rc, 0, 2, 1550, 2100))
        assertFalse(rc.awaitingClear)
        assertEquals(RollCall.Hint.WAITING, rc.hint())
        val lockAt = hold(rc, 1, 3, 2150, 4000)
        assertTrue(lockAt >= 2150 + 1000)
        assertEquals(1, rc.enrol())
        assertTrue(rc.canDraw())
    }

    @Test
    fun fullRosterStopsLocking() {
        val rc = call()
        var t = 0L
        for (i in 0 until 4) {
            val lockAt = hold(rc, 1, i + 1, t, t + 2000)
            assertTrue(lockAt >= 0)
            assertEquals(i, rc.enrol())
            t = lockAt + 50
            assertEquals(-1L, hold(rc, 0, i + 1, t, t + 600))
            t += 650
        }
        assertTrue(rc.isFull())
        assertEquals(-1L, hold(rc, 1, 9, t, t + 3000))
        assertEquals(RollCall.Hint.FULL, rc.hint())
        assertEquals(-1, rc.enrol())
    }

    @Test
    fun removeAndClear() {
        val rc = call()
        assertTrue(hold(rc, 1, 1, 0, 1000) >= 0)
        rc.enrol()
        assertEquals(-1L, hold(rc, 0, 1, 1050, 1600))
        assertTrue(hold(rc, 1, 2, 1650, 2700) >= 0)
        rc.enrol()
        assertTrue(rc.canDraw())
        assertFalse(rc.remove(5))
        assertTrue(rc.remove(0))
        assertEquals(1, rc.count)
        assertFalse(rc.canDraw())
        rc.clear()
        assertEquals(0, rc.count)
        assertFalse(rc.awaitingClear)
        assertEquals(RollCall.Hint.FIRST, rc.hint())
    }

    @Test
    fun arcRisesWhileHoldingAndIsZeroOtherwise() {
        val rc = call()
        rc.update(1, 1, 0)
        assertEquals(0f, rc.arc(0), 0.001f)
        rc.update(1, 1, 500)
        assertEquals(0.5f, rc.arc(500), 0.001f)
        assertTrue(rc.isAnimating(500))
        rc.update(2, 2, 600)
        // Collapsing, then zero.
        assertTrue(rc.arc(600) > 0f)
        assertEquals(0f, rc.arc(800), 0.001f)
        assertFalse(rc.isAnimating(800))
    }
}
