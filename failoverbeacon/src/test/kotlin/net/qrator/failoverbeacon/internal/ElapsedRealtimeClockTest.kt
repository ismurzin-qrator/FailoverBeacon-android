package net.qrator.failoverbeacon.internal

import android.os.SystemClock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
class ElapsedRealtimeClockTest {
    @Test
    fun `reads the elapsed realtime`() {
        assertEquals(SystemClock.elapsedRealtimeNanos(), ElapsedRealtimeClock.nanoTime())
    }

    @Test
    fun `advances with the elapsed realtime`() {
        val before = ElapsedRealtimeClock.nanoTime()

        ShadowSystemClock.advanceBy(Duration.ofMillis(5))

        assertEquals(5_000_000, ElapsedRealtimeClock.nanoTime() - before)
    }
}
