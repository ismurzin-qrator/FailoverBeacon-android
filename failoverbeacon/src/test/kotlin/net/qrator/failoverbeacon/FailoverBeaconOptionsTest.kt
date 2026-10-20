package net.qrator.failoverbeacon

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FailoverBeaconOptionsTest {
    @Test
    fun `defaults match documented values`() {
        val options = FailoverBeaconOptions.Builder().build()

        assertEquals(60_000L, options.uploadPeriodMillis)
        assertEquals(1000, options.diskQueueSize)
        assertEquals(1000, options.memoryQueueSize)
    }

    @Test
    fun `uploadPeriod rejects periods shorter than 1 ms`() {
        assertFailsWith<IllegalArgumentException> {
            FailoverBeaconOptions.Builder().uploadPeriod(999_999, TimeUnit.NANOSECONDS)
        }
    }

    @Test
    fun `queue sizes reject non-positive values`() {
        val builder = FailoverBeaconOptions.Builder()

        assertFailsWith<IllegalArgumentException> { builder.diskQueueSize(0) }
        assertFailsWith<IllegalArgumentException> { builder.diskQueueSize(-1) }
        assertFailsWith<IllegalArgumentException> { builder.memoryQueueSize(0) }
        assertFailsWith<IllegalArgumentException> { builder.memoryQueueSize(-1) }
    }
}
