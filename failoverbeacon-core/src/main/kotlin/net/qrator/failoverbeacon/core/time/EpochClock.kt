package net.qrator.failoverbeacon.core.time

fun interface EpochClock {
    fun nowMillis(): Long
}
