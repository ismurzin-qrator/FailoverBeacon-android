package net.qrator.failoverbeacon.failover.model

import net.qrator.failoverbeacon.core.time.MonotonicClock

internal class RefreshTimer(private val clock: MonotonicClock, private val intervalNanos: Long) {
    fun now(): Long = clock.nanoTime()

    fun isExpired(atNanos: Long): Boolean = now() - atNanos >= intervalNanos
}
