package net.qrator.failoverbeacon.failover.model

import net.qrator.failoverbeacon.core.time.EpochClock

internal class MaxAge(private val clock: EpochClock, private val millis: Long) {
    fun now(): Long = clock.nowMillis()

    fun isOver(atMillis: Long): Boolean = now() - atMillis >= millis

    fun accepts(atMillis: Long): Boolean = atMillis <= now() && !isOver(atMillis)
}
