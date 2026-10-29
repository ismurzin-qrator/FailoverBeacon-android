package net.qrator.failoverbeacon.failover

import java.util.concurrent.TimeUnit

class FailoverSettings(
    val resolveThreads: Int = 4,
    val maxAddressAgeNanos: Long = TimeUnit.HOURS.toNanos(1),
    val threadKeepAliveMillis: Long = 30_000,
    val probeThreads: Int = 4,
    val probeTimeoutMillis: Int = 5_000
)
