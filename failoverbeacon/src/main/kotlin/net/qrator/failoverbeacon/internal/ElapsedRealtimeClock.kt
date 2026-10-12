package net.qrator.failoverbeacon.internal

import android.os.SystemClock
import net.qrator.failoverbeacon.core.time.MonotonicClock

/** Time since boot, including deep sleep. */
internal object ElapsedRealtimeClock : MonotonicClock {
    override fun nanoTime(): Long = SystemClock.elapsedRealtimeNanos()
}
