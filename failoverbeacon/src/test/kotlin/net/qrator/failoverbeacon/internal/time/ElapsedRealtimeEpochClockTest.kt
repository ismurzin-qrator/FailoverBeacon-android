package net.qrator.failoverbeacon.internal.time

import android.os.SystemClock
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ElapsedRealtimeEpochClockTest {
    @Test
    fun `starts at the device clock and advances with the elapsed realtime`() {
        val clock = ElapsedRealtimeEpochClock()
        val start = clock.nowMillis()

        SystemClock.sleep(65_000)

        assertTrue(abs(start - System.currentTimeMillis()) < 1_000)
        assertEquals(start + 65_000, clock.nowMillis())
    }
}
