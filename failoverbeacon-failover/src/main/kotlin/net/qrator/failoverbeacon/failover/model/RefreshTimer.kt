package net.qrator.failoverbeacon.failover.model

import net.qrator.failoverbeacon.core.time.EpochClock

internal class RefreshTimer(private val clock: EpochClock, private val intervalMillis: Long) {
    fun now(): Long = clock.nowMillis()

    fun isExpired(atMillis: Long): Boolean = now() - atMillis >= intervalMillis
}
