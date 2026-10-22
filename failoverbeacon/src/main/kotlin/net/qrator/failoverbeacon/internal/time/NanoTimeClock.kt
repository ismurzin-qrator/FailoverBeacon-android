package net.qrator.failoverbeacon.internal.time

import net.qrator.failoverbeacon.core.time.MonotonicClock

internal object NanoTimeClock : MonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}
