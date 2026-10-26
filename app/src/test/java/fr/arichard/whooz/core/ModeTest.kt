package fr.arichard.whooz.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeTest {
    @Test
    fun everyModeRoundTrips() {
        for (m in Mode.FINGER_MODES) assertEquals(m, Mode.parse(m.serialize()))
        for (m in Mode.CAMERA_MODES) assertEquals(m, Mode.parse(m.serialize()))
        assertEquals("winner:1", Mode.Winner(1).serialize())
        assertEquals("order", Mode.Order.serialize())
        assertEquals("teams:3", Mode.Teams(3).serialize())
    }

    @Test
    fun unknownInputFallsBackToWinnerOne() {
        assertEquals(Mode.Winner(1), Mode.parse(null))
        assertEquals(Mode.Winner(1), Mode.parse(""))
        assertEquals(Mode.Winner(1), Mode.parse("banana"))
        assertEquals(Mode.Winner(1), Mode.parse("winner:"))
        assertEquals(Mode.Winner(1), Mode.parse("winner:x"))
        assertEquals(Mode.Winner(1), Mode.parse("teams"))
        assertEquals(Mode.Winner(1), Mode.parse("teams:1"))
        assertEquals(Mode.Winner(1), Mode.parse("teams:4"))
        assertEquals(Mode.Winner(1), Mode.parse("winner:0"))
        assertEquals(Mode.Winner(1), Mode.parse("winner:4"))
        assertEquals(Mode.Winner(2), Mode.parse(" winner:2 "))
        assertEquals(Mode.DEFAULT, Mode.Winner(1))
    }

    @Test
    fun constructorsRejectNonsense() {
        assertThrows { Mode.Winner(0) }
        assertThrows { Mode.Teams(1) }
        assertEquals(3, Mode.MAX_WINNERS)
        assertEquals(3, Mode.MAX_TEAMS)
    }

    @Test
    fun cameraOffersOnlyPickModes() {
        assertEquals(listOf<Mode>(Mode.Winner(1), Mode.Winner(2)), Mode.CAMERA_MODES)
        assertEquals(6, Mode.FINGER_MODES.size)
        assertTrue(Mode.FINGER_MODES.containsAll(Mode.CAMERA_MODES))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
