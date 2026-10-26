package fr.arichard.whooz.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test
    fun numericPartsNotLexicographic() {
        assertTrue(VersionCompare.isNewer("1.10", "1.9"))
        assertFalse(VersionCompare.isNewer("1.9", "1.10"))
        assertTrue(VersionCompare.isNewer("2.0", "1.99"))
        assertTrue(VersionCompare.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun vPrefixAndWhitespaceIgnored() {
        assertTrue(VersionCompare.isNewer("v1.1", "1.0"))
        assertTrue(VersionCompare.isNewer("1.1", "v1.0"))
        assertEquals(0, VersionCompare.compareVersions(" V1.2 ", "1.2"))
        assertEquals("1.2", VersionCompare.normalize(" v1.2 "))
        assertEquals("", VersionCompare.normalize(null))
    }

    @Test
    fun equalIsNotNewer() {
        assertFalse(VersionCompare.isNewer("1.0", "1.0"))
        assertFalse(VersionCompare.isNewer("1.0", "1.0.0"))
        assertFalse(VersionCompare.isNewer("1", "1.0"))
        assertEquals(0, VersionCompare.compareVersions("1.0.0", "1"))
    }

    @Test
    fun garbageNeverNewer() {
        assertFalse(VersionCompare.isNewer("garbage", "1.0"))
        assertFalse(VersionCompare.isNewer("", "1.0"))
        assertFalse(VersionCompare.isNewer(null, "1.0"))
        assertFalse(VersionCompare.isNewer("..", "0.0"))
        assertFalse(VersionCompare.isNewer("abc.def", "0"))
        // A garbage local version must not block a real update either.
        assertTrue(VersionCompare.isNewer("1.0", "garbage"))
    }

    @Test
    fun leadingDigitsOnlyCountInAPart() {
        assertTrue(VersionCompare.isNewer("1.3-rc1", "1.2"))
        assertFalse(VersionCompare.isNewer("1.2-rc1", "1.2"))
        assertEquals(0, VersionCompare.compareVersions("1.2beta", "1.2"))
    }

    @Test
    fun neverThrows() {
        val inputs = arrayOf(
            null, "", " ", "v", "V", ".", "...", "1..2", "-1", "1.-2", "a.b.c", "1.2.3.4.5.6.7.8",
            "99999999999999999999999", "1e10", "١٢", "\u0000", "v v1", "1,2",
        )
        for (a in inputs) for (b in inputs) {
            VersionCompare.compareVersions(a, b)
            VersionCompare.isNewer(a, b)
        }
        // Huge numbers are capped, never negative: still newer than anything sane.
        assertTrue(VersionCompare.isNewer("99999999999999999999999", "1.0"))
    }

    @Test
    fun antisymmetric() {
        val a = "1.4.2"
        val b = "1.4.10"
        assertEquals(-VersionCompare.compareVersions(a, b), VersionCompare.compareVersions(b, a))
    }
}
