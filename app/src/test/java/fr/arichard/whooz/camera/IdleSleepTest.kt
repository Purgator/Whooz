package fr.arichard.whooz.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Battery rule: an idle camera screen with nobody in front of it goes to sleep. */
class IdleSleepTest {
    private val timeout = 180_000L

    @Test
    fun sleepsExactlyAtTheTimeout() {
        val s = IdleSleep(timeout)
        s.activity(1_000L)
        assertFalse(s.shouldSleep(1_000L + timeout - 1))
        assertTrue(s.shouldSleep(1_000L + timeout))
        assertTrue(s.shouldSleep(1_000L + 10 * timeout))
    }

    @Test
    fun activityRestartsTheClock() {
        val s = IdleSleep(timeout)
        s.activity(0L)
        s.activity(100_000L)
        assertFalse(s.shouldSleep(timeout))
        assertFalse(s.shouldSleep(100_000L + timeout - 1))
        assertTrue(s.shouldSleep(100_000L + timeout))
    }

    @Test
    fun delayIsTheRemainderAndNeverNegative() {
        val s = IdleSleep(timeout)
        s.activity(5_000L)
        assertEquals(timeout, s.delayMs(5_000L))
        assertEquals(timeout - 60_000L, s.delayMs(65_000L))
        assertEquals(0L, s.delayMs(5_000L + timeout))
        assertEquals(0L, s.delayMs(5_000L + timeout + 1))
    }

    @Test
    fun lazyCheckRepostsForTheRemainderThenSleeps() {
        // The owner posts one check per delay; activity in between pushes the check back.
        val s = IdleSleep(timeout)
        s.activity(0L)
        var now = s.delayMs(0L)
        assertEquals(timeout, now)
        // A face showed up 30 s before the check fires: the check re-posts for the remainder
        // of a full timeout counted from that face, and only then sleeps.
        s.activity(now - 30_000L)
        val delay = s.delayMs(now)
        assertEquals(timeout - 30_000L, delay)
        assertFalse(s.shouldSleep(now + delay - 1))
        now += delay
        assertTrue(s.shouldSleep(now))
    }
}
