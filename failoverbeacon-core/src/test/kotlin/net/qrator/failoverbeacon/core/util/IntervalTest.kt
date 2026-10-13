package net.qrator.failoverbeacon.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntervalTest {
    @Test
    fun `duration is end minus start`() {
        assertEquals(5, interval(10, 15).durationNanos)
    }

    @Test
    fun `duration is null without a start or an end`() {
        assertNull(Interval().durationNanos)
        assertNull(Interval(startNanos = 10).durationNanos)
        assertNull(Interval().apply { endNanos = 15 }.durationNanos)
    }

    @Test
    fun `duration is null when the end comes before the start`() {
        assertNull(interval(15, 10).durationNanos)
    }

    @Test
    fun `contains times strictly between start and end`() {
        val interval = interval(10, 20)

        assertTrue(interval.contains(15))
        assertFalse(interval.contains(10))
        assertFalse(interval.contains(20))
        assertFalse(interval.contains(5))
    }

    @Test
    fun `an interval without an end contains every later time`() {
        val interval = Interval(startNanos = 10)

        assertTrue(interval.contains(Long.MAX_VALUE - 1))
        assertFalse(interval.contains(10))
    }

    @Test
    fun `an interval without a start contains nothing`() {
        assertFalse(Interval().apply { endNanos = 20 }.contains(15))
    }

    @Test
    fun `overlapping intervals overlap each other`() {
        val first = interval(10, 20)
        val second = interval(15, 25)

        assertTrue(first.overlaps(second))
        assertTrue(second.overlaps(first))
    }

    @Test
    fun `intervals that only touch do not overlap`() {
        assertFalse(interval(10, 20).overlaps(interval(20, 30)))
        assertFalse(interval(20, 30).overlaps(interval(10, 20)))
    }

    @Test
    fun `an interval without an end overlaps a later interval`() {
        assertTrue(Interval(startNanos = 10).overlaps(interval(100, 200)))
        assertTrue(interval(100, 200).overlaps(Interval(startNanos = 10)))
    }

    @Test
    fun `an interval without a start overlaps nothing`() {
        val noStart = Interval().apply { endNanos = 20 }

        assertFalse(noStart.overlaps(interval(10, 30)))
        assertFalse(interval(10, 30).overlaps(noStart))
    }

    private fun interval(start: Long, end: Long) = Interval(start).apply { endNanos = end }
}
