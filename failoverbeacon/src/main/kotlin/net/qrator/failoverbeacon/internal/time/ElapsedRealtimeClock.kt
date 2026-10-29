package net.qrator.failoverbeacon.internal.time

import android.os.SystemClock
import net.qrator.failoverbeacon.core.time.MonotonicClock

internal object ElapsedRealtimeClock : MonotonicClock {
    override fun nanoTime(): Long = SystemClock.elapsedRealtimeNanos()
}
