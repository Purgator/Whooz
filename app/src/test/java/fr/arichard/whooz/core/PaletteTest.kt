package fr.arichard.whooz.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    @Test
    fun sixteenDistinctOpaqueColors() {
        assertEquals(16, Palette.SIZE)
        assertEquals(Palette.SIZE, Palette.COLORS.size)
        val seen = HashSet<Int>()
        for (c in Palette.COLORS) {
            assertEquals(0xFF, (c ushr 24))
            assertTrue("duplicate color", seen.add(c))
        }
        assertEquals(0xFFFF3B6B.toInt(), Palette.COLORS[0])
        assertEquals(0xFFFF8C3B.toInt(), Palette.COLORS[11])
        assertEquals(0xFFFFFFFF.toInt(), Palette.COLORS[12])
        assertEquals(0xFFFF5CE1.toInt(), Palette.COLORS[15])
        assertEquals(0xFF0B0E14.toInt(), Palette.BACKGROUND)
    }

    @Test
    fun teamColorsAreTheFirstK() {
        for (k in 0 until 3) assertEquals(Palette.COLORS[k], Palette.team(k))
        for (s in 0 until Palette.SIZE) assertEquals(Palette.COLORS[s], Palette.of(s))
    }

    @Test
    fun slotsWrapInsteadOfThrowing() {
        assertEquals(Palette.COLORS[0], Palette.of(16))
        assertEquals(Palette.COLORS[15], Palette.of(-1))
        assertEquals(Palette.COLORS[3], Palette.of(35))
    }

    @Test
    fun withAlphaReplacesOnlyTheAlphaByte() {
        val c = 0xFF123456.toInt()
        assertEquals(0x80123456.toInt(), Palette.withAlpha(c, 0x80))
        assertEquals(0x00123456, Palette.withAlpha(c, 0))
        assertEquals(0xFF123456.toInt(), Palette.withAlpha(c, 999))
        assertEquals(0x00123456, Palette.withAlpha(c, -5))
        assertEquals(0xFF123456.toInt(), Palette.withAlpha(c, 1f))
        assertEquals(0x00123456, Palette.withAlpha(c, 0f))
        assertEquals(0x80123456.toInt(), Palette.withAlpha(c, 0.5f))
        assertEquals(0xFF123456.toInt(), Palette.withAlpha(c, 2f))
    }
}
