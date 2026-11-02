package net.qrator.failoverbeacon

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    }

    @Test
    fun `network change options are set independently`() {
        val noPreresolve = FailoverBeaconOptions.Builder().preresolveOnNetworkChange(false).build()
        val noProbes = FailoverBeaconOptions.Builder().probeOnNetworkChange(false).build()

        assertFalse(noPreresolve.preresolveOnNetworkChange)
        assertTrue(noPreresolve.probeOnNetworkChange)
        assertTrue(noProbes.preresolveOnNetworkChange)
        assertFalse(noProbes.probeOnNetworkChange)
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
    fun `queue sizes reject non-positive values`() {
        val builder = FailoverBeaconOptions.Builder()

        assertFailsWith<IllegalArgumentException> { builder.diskQueueBytes(0) }
        assertFailsWith<IllegalArgumentException> { builder.diskQueueBytes(-1) }
        assertFailsWith<IllegalArgumentException> { builder.memoryQueueSize(0) }
        assertFailsWith<IllegalArgumentException> { builder.memoryQueueSize(-1) }
    }
}
