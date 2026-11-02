package net.qrator.failoverbeacon.failover.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefreshTimerTest {
    private var now = 100L
    private val timer = RefreshTimer({ now }, intervalNanos = 50)

    @Test
    fun `a time expires when the interval has passed`() {
        assertEquals(100, timer.now())
        assertFalse(timer.isExpired(atNanos = 51))
        assertTrue(timer.isExpired(atNanos = 50))
    }
}
