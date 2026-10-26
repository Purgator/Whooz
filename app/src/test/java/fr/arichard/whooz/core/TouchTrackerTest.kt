package fr.arichard.whooz.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchTrackerTest {
    private val finger = TouchTracker.TOOL_TYPE_FINGER

    @Test
    fun slotsAreLowestFreeAndKeptWhenAnotherLifts() {
        val t = TouchTracker()
        assertTrue(t.down(10, 1f, 1f, finger))
        assertTrue(t.down(11, 2f, 2f, finger))
        assertTrue(t.down(12, 3f, 3f, finger))
        assertEquals(0, t.slotAt(t.indexOfId(10)))
        assertEquals(1, t.slotAt(t.indexOfId(11)))
        assertEquals(2, t.slotAt(t.indexOfId(12)))

        assertEquals(1, t.up(11))
        assertEquals(2, t.count)
        // The survivors keep their color; the freed slot is the lowest for the next finger.
        assertEquals(0, t.slotAt(t.indexOfId(10)))
        assertEquals(2, t.slotAt(t.indexOfId(12)))
        assertTrue(t.down(13, 4f, 4f, finger))
        assertEquals(1, t.slotAt(t.indexOfId(13)))
    }

    @Test
    fun moveReportsOnePixelThreshold() {
        val t = TouchTracker()
        t.down(1, 100f, 100f, finger)
        assertFalse(t.move(1, 100.5f, 100.2f))
        assertEquals(100.5f, t.xAt(0), 0f)
        assertTrue(t.move(1, 101.5f, 100.2f))
        assertFalse(t.move(99, 0f, 0f))
    }

    @Test
    fun idsReusedAfterClear() {
        val t = TouchTracker()
        t.down(0, 1f, 1f, finger)
        t.down(1, 2f, 2f, finger)
        t.clear()
        assertEquals(0, t.count)
        assertTrue(t.down(0, 5f, 5f, finger))
        assertTrue(t.down(1, 6f, 6f, finger))
        assertEquals(0, t.slotAt(0))
        assertEquals(1, t.slotAt(1))
    }

    @Test
    fun seventeenthPointerIgnored() {
        val t = TouchTracker()
        for (id in 0 until 16) assertTrue(t.down(id, id.toFloat(), 0f, finger))
        assertFalse(t.down(16, 0f, 0f, finger))
        assertEquals(16, t.count)
        assertEquals(-1, t.indexOfId(16))
        assertEquals(-1, t.up(16))
    }

    @Test
    fun nonFingerToolTypesIgnoredUnknownAccepted() {
        val t = TouchTracker()
        assertFalse(t.down(1, 0f, 0f, TouchTracker.TOOL_TYPE_STYLUS))
        assertFalse(t.down(2, 0f, 0f, TouchTracker.TOOL_TYPE_MOUSE))
        assertFalse(t.down(3, 0f, 0f, TouchTracker.TOOL_TYPE_ERASER))
        assertEquals(0, t.count)
        assertTrue(t.down(4, 0f, 0f, TouchTracker.TOOL_TYPE_UNKNOWN))
        assertEquals(1, t.count)
    }

    @Test
    fun setVersionBumpsOnlyOnSetChanges() {
        val t = TouchTracker()
        val v0 = t.setVersion
        t.down(1, 0f, 0f, finger)
        val v1 = t.setVersion
        assertTrue(v1 > v0)
        t.move(1, 50f, 50f)
        assertEquals(v1, t.setVersion)
        t.down(2, 0f, 0f, finger)
        val v2 = t.setVersion
        assertTrue(v2 > v1)
        t.up(2)
        assertTrue(t.setVersion > v2)
        val v3 = t.setVersion
        t.up(42) // unknown id: no-op
        assertEquals(v3, t.setVersion)
        assertEquals(1, t.count)
        t.clear()
        assertTrue(t.setVersion > v3)
        val v4 = t.setVersion
        t.clear() // nothing to forget: no change of set
        assertEquals(v4, t.setVersion)
    }

    @Test
    fun absorbedPointersAreTrackedButNotParticipants() {
        val t = TouchTracker()
        t.down(1, 0f, 0f, finger)
        t.down(2, 0f, 0f, finger)
        val v = t.setVersion
        assertTrue(t.down(3, 9f, 9f, finger, absorbed = true))
        assertEquals(3, t.count)
        assertEquals(2, t.participantCount)
        assertEquals(v, t.setVersion)
        assertTrue(t.isAbsorbed(2))
        assertFalse(t.isAbsorbed(0))

        val out = IntArray(Palette.SIZE)
        assertEquals(2, t.participantsSnapshot(out))
        assertEquals(0, out[0])
        assertEquals(1, out[1])

        assertEquals(2, t.up(3))
        assertEquals(v, t.setVersion)
        assertEquals(2, t.participantCount)
    }

    @Test
    fun cancelledUpRemovesAndFlagsForNoHaptic() {
        val t = TouchTracker()
        t.down(1, 0f, 0f, finger)
        t.down(2, 0f, 0f, finger)
        assertEquals(1, t.up(2, cancelled = true))
        assertTrue(t.lastUpCancelled)
        assertEquals(1, t.count)
        assertEquals(0, t.up(1))
        assertFalse(t.lastUpCancelled)
    }

    @Test
    fun duplicateDownIsAMove() {
        val t = TouchTracker()
        assertTrue(t.down(1, 0f, 0f, finger))
        val v = t.setVersion
        assertFalse(t.down(1, 7f, 8f, finger))
        assertEquals(1, t.count)
        assertEquals(7f, t.xAt(0), 0f)
        assertEquals(v, t.setVersion)
    }

    @Test
    fun indexOfSlotFindsOwner() {
        val t = TouchTracker()
        t.down(5, 0f, 0f, finger)
        t.down(6, 0f, 0f, finger)
        assertEquals(1, t.indexOfSlot(1))
        assertEquals(6, t.idAt(t.indexOfSlot(1)))
        assertEquals(-1, t.indexOfSlot(2))
    }
}
