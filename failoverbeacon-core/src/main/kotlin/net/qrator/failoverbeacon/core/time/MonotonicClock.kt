package net.qrator.failoverbeacon.core.time

/** A clock whose readings only make sense as differences between two readings. */
fun interface MonotonicClock {
    fun nanoTime(): Long
}
