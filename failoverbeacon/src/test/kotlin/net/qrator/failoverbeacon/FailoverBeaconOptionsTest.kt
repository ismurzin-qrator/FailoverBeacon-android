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
    fun `setters override defaults`() {
        val options = FailoverBeaconOptions.Builder()
            .uploadPeriod(2, TimeUnit.MINUTES)
            .diskQueueSize(10)
            .memoryQueueSize(20)
            .build()

        assertEquals(120_000L, options.uploadPeriodMillis)
        assertEquals(10, options.diskQueueSize)
        assertEquals(20, options.memoryQueueSize)
    }

    @Test
    fun `uploadPeriod rejects non-positive periods`() {
        val builder = FailoverBeaconOptions.Builder()

        assertFailsWith<IllegalArgumentException> { builder.uploadPeriod(0, TimeUnit.SECONDS) }
        assertFailsWith<IllegalArgumentException> { builder.uploadPeriod(-1, TimeUnit.SECONDS) }
    }

    @Test
    fun `uploadPeriod rejects periods shorter than 1 ms`() {
        assertFailsWith<IllegalArgumentException> {
            FailoverBeaconOptions.Builder().uploadPeriod(999_999, TimeUnit.NANOSECONDS)
        }
    }

    @Test
    fun `uploadPeriod accepts exactly 1 ms`() {
        val options = FailoverBeaconOptions.Builder()
            .uploadPeriod(1_000_000, TimeUnit.NANOSECONDS)
            .build()

        assertEquals(1L, options.uploadPeriodMillis)
    }

    @Test
    fun `queue sizes reject non-positive values`() {
        val builder = FailoverBeaconOptions.Builder()

        assertFailsWith<IllegalArgumentException> { builder.diskQueueSize(0) }
        assertFailsWith<IllegalArgumentException> { builder.diskQueueSize(-1) }
        assertFailsWith<IllegalArgumentException> { builder.memoryQueueSize(0) }
        assertFailsWith<IllegalArgumentException> { builder.memoryQueueSize(-1) }
    }

    @Test
    fun `rejected value keeps the previous one`() {
        val builder = FailoverBeaconOptions.Builder().diskQueueSize(10)

        assertFailsWith<IllegalArgumentException> { builder.diskQueueSize(0) }

        assertEquals(10, builder.build().diskQueueSize)
    }
}
