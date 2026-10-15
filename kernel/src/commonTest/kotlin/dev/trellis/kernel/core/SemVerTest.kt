package dev.trellis.kernel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemVerTest {

    @Test
    fun parsesSimpleVersion() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("1.2.3"))
    }

    @Test
    fun dropsPrereleaseAndBuildMetadata() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("1.2.3-rc.1+build.5"))
    }

    @Test
    fun comparesMajorThenMinorThenPatch() {
        assertTrue(SemVer(2, 0, 0) > SemVer(1, 9, 9))
        assertTrue(SemVer(1, 2, 0) > SemVer(1, 1, 9))
        assertTrue(SemVer(1, 1, 2) > SemVer(1, 1, 1))
        assertEquals(0, SemVer(1, 1, 1).compareTo(SemVer(1, 1, 1)))
    }

    @Test
    fun rejectsMalformedVersion() {
        assertFailsWith<IllegalArgumentException> { SemVer.parse("1.2") }
        assertFailsWith<IllegalArgumentException> { SemVer.parse("a.b.c") }
    }
}

class VersionRangeTest {

    @Test
    fun wildcardAcceptsEverything() {
        assertTrue(SemVer(9, 9, 9) in VersionRange.ANY)
        assertTrue(SemVer(0, 0, 1) in VersionRange.parse("*"))
        assertTrue(SemVer(1, 0, 0) in VersionRange.parse(""))
    }

    @Test
    fun conjunctionOfBounds() {
        val r = VersionRange.parse(">=1.0.0 <2.0.0")
        assertTrue(SemVer(1, 0, 0) in r)
        assertTrue(SemVer(1, 9, 9) in r)
        assertFalse(SemVer(2, 0, 0) in r)
        assertFalse(SemVer(0, 9, 9) in r)
    }

    @Test
    fun openEndedBounds() {
        assertTrue(SemVer(99, 0, 0) in VersionRange.parse(">=1.0.0"))
        assertFalse(SemVer(0, 9, 0) in VersionRange.parse(">=1.0.0"))
        assertTrue(SemVer(0, 1, 0) in VersionRange.parse("<2.0.0"))
    }

    @Test
    fun exactMatchOperators() {
        for (expr in listOf("==1.2.3", "=1.2.3")) {
            assertTrue(SemVer(1, 2, 3) in VersionRange.parse(expr), expr)
            assertFalse(SemVer(1, 2, 4) in VersionRange.parse(expr), expr)
        }
    }

    @Test
    fun rejectsUnknownOperator() {
        assertFailsWith<IllegalArgumentException> { VersionRange.parse("~>1.0.0") }
    }
}