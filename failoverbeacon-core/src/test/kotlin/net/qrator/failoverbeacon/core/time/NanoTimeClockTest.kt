package net.qrator.failoverbeacon.core.time

import kotlin.test.Test
import kotlin.test.assertTrue

class NanoTimeClockTest {
    @Test
    fun `reads System nanoTime`() {
        val before = System.nanoTime()
        val reading = NanoTimeClock.nanoTime()
        val after = System.nanoTime()

        assertTrue(reading in before..after)
    }
}
