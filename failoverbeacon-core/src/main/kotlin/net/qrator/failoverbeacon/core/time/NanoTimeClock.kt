package net.qrator.failoverbeacon.core.time

/** [System.nanoTime] stops while the device is in deep sleep. */
object NanoTimeClock : MonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}
