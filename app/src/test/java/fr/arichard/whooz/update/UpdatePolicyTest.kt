package fr.arichard.whooz.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val day = UpdatePolicy.CHECK_INTERVAL_MS
    private val now = 1_700_000_000_000L

    @Test
    fun firstCheckIsAlwaysDue() {
        assertTrue(UpdatePolicy.isCheckDue(now, 0L, deferred = false, unmetered = false))
        assertTrue(UpdatePolicy.isCheckDue(now, -5L, deferred = false, unmetered = false))
    }

    @Test
    fun throttledWithinTwentyFourHours() {
        assertFalse(UpdatePolicy.isCheckDue(now, now, deferred = false, unmetered = true))
        assertFalse(UpdatePolicy.isCheckDue(now, now - day + 1, deferred = false, unmetered = true))
        assertTrue(UpdatePolicy.isCheckDue(now, now - day, deferred = false, unmetered = false))
        assertTrue(UpdatePolicy.isCheckDue(now, now - 3 * day, deferred = false, unmetered = false))
    }

    @Test
    fun clockMovedBackwardsDoesNotBlockForDays() {
        assertTrue(UpdatePolicy.isCheckDue(now, now + 1, deferred = false, unmetered = false))
        assertTrue(UpdatePolicy.isCheckDue(now, now + 10 * day, deferred = false, unmetered = false))
    }

    @Test
    fun deferredRetriesOnlyOnUnmetered() {
        val recent = now - 60_000L
        assertTrue(UpdatePolicy.isCheckDue(now, recent, deferred = true, unmetered = true))
        assertFalse(UpdatePolicy.isCheckDue(now, recent, deferred = true, unmetered = false))
        assertFalse(UpdatePolicy.isCheckDue(now, recent, deferred = false, unmetered = true))
        // Once the day elapsed the network type no longer matters.
        assertTrue(UpdatePolicy.isCheckDue(now, now - day, deferred = true, unmetered = false))
    }

    @Test
    fun downloadsOnUnmeteredOrWhenForced() {
        assertTrue(UpdatePolicy.shouldDownload(unmetered = true, force = false))
        assertTrue(UpdatePolicy.shouldDownload(unmetered = false, force = true))
        assertTrue(UpdatePolicy.shouldDownload(unmetered = true, force = true))
        assertFalse(UpdatePolicy.shouldDownload(unmetered = false, force = false))
    }

    @Test
    fun dismissedVersionHiddenForADay() {
        val at = now - 3_600_000L
        assertTrue(UpdatePolicy.isDismissed("1.2", "1.2", at, now))
        assertTrue(UpdatePolicy.isDismissed("1.2", "1.2", at, at))
        assertTrue(UpdatePolicy.isDismissed("1.2", "1.2", at, at + UpdatePolicy.DISMISS_INTERVAL_MS - 1))
        assertFalse(UpdatePolicy.isDismissed("1.2", "1.2", at, at + UpdatePolicy.DISMISS_INTERVAL_MS))
        assertFalse(UpdatePolicy.isDismissed("1.2", "1.2", at, at + 5 * day))
    }

    @Test
    fun otherVersionIsNotDismissed() {
        assertFalse(UpdatePolicy.isDismissed("1.3", "1.2", now, now))
        assertFalse(UpdatePolicy.isDismissed("1.2", "", now, now))
        assertFalse(UpdatePolicy.isDismissed("", "", now, now))
        assertFalse(UpdatePolicy.isDismissed("1.2", "1.2", 0L, now))
    }

    @Test
    fun dismissalInTheFutureIsIgnored() {
        assertFalse(UpdatePolicy.isDismissed("1.2", "1.2", now + 1, now))
    }

    @Test
    fun onlyRealChecksMoveTheThrottle() {
        assertTrue(UpdatePolicy.recordsCheck(reachedServer = true))
        assertFalse(UpdatePolicy.recordsCheck(reachedServer = false))
    }
}
