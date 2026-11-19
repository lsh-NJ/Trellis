package dev.trellis.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HlcTest {

    @Test
    fun comparesByWallThenCounterThenDevice() {
        assertTrue(Hlc(1, 0, "dev_a") < Hlc(2, 0, "dev_a"))
        assertTrue(Hlc(1, 0, "dev_a") < Hlc(1, 1, "dev_a"))
        assertTrue(Hlc(1, 1, "dev_a") < Hlc(1, 1, "dev_b"))
        assertEquals(0, Hlc(1, 1, "dev_a").compareTo(Hlc(1, 1, "dev_a")))
    }

    @Test
    fun encodeRoundTrip() {
        val hlc = Hlc(1763538000123L, 17, "dev_01JABC")
        assertEquals(hlc, Hlc.parse(hlc.encode()))
        assertNotEquals(Hlc(1, 0, "dev_a"), Hlc(1, 0, "dev_b"))
    }

    @Test
    fun parseRejectsGarbage() {
        assertEquals(null, Hlc.parse(""))
        assertEquals(null, Hlc.parse("abc"))
        assertEquals(null, Hlc.parse("1-2"))
        assertEquals(null, Hlc.parse("-1-0-dev_a"))
    }

    @Test
    fun clockIsMonotonicWithinSameMillisecond() {
        var now = 1_000L
        val clock = HlcClock("dev_a") { now }
        val first = clock.next()
        val second = clock.next()
        now = 1_000L
        val third = clock.next()
        assertTrue(first < second)
        assertTrue(second < third)
    }

    @Test
    fun observeAdvancesPastRemote() {
        val local = HlcClock("dev_a") { 1_000L }
        local.next()
        val remote = Hlc(5_000L, 3, "dev_b")
        local.observe(remote)
        assertTrue(local.last() > remote)
        val before = local.last()
        assertTrue(local.next() > before)
    }
}
