package net.qrator.failoverbeacon

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FailoverBeaconOptionsTest {
    @Test
    fun `defaults match documented values`() {
        val options = FailoverBeaconOptions.Builder().build()

        assertEquals(60_000L, options.uploadPeriodMillis)
        assertEquals(5L * 1024 * 1024, options.diskQueueBytes)
        assertEquals(1000, options.memoryQueueSize)
        assertTrue(options.preresolveOnNetworkChange)
        assertTrue(options.probeOnNetworkChange)
        assertEquals(600_000L, options.refreshIntervalMillis)
        assertTrue(options.fallbackWithoutEch)
    }

    @Test
    fun `uploadPeriod accepts periods from 1 second to 8 hours`() {
        val builder = FailoverBeaconOptions.Builder()

        val shortest = builder.uploadPeriod(1, TimeUnit.SECONDS).build()
        val longest = builder.uploadPeriod(8, TimeUnit.HOURS).build()

        assertEquals(1000L, shortest.uploadPeriodMillis)
        assertEquals(28_800_000L, longest.uploadPeriodMillis)
        assertFailsWith<IllegalArgumentException> {
            builder.uploadPeriod(999_999_999, TimeUnit.NANOSECONDS)
        }
        assertFailsWith<IllegalArgumentException> {
            builder.uploadPeriod(28_800_001, TimeUnit.MILLISECONDS)
        }
        assertFailsWith<IllegalArgumentException> {
            builder.uploadPeriod(Long.MAX_VALUE, TimeUnit.DAYS)
        }
    }

    @Test
    fun `refreshInterval accepts intervals from 1 minute to 1 hour`() {
        val builder = FailoverBeaconOptions.Builder()

        val shortest = builder.refreshInterval(1, TimeUnit.MINUTES).build()
        val longest = builder.refreshInterval(1, TimeUnit.HOURS).build()

        assertEquals(60_000L, shortest.refreshIntervalMillis)
        assertEquals(3_600_000L, longest.refreshIntervalMillis)
        assertFailsWith<IllegalArgumentException> {
            builder.refreshInterval(59_999, TimeUnit.MILLISECONDS)
        }
        assertFailsWith<IllegalArgumentException> {
            builder.refreshInterval(3_600_001, TimeUnit.MILLISECONDS)
        }
        assertFailsWith<IllegalArgumentException> {
            builder.refreshInterval(Long.MAX_VALUE, TimeUnit.DAYS)
        }
    }

    @Test
    fun `queue sizes reject non-positive values`() {
        val builder = FailoverBeaconOptions.Builder()

        assertFailsWith<IllegalArgumentException> { builder.diskQueueBytes(0) }
        assertFailsWith<IllegalArgumentException> { builder.memoryQueueSize(0) }
    }
}
