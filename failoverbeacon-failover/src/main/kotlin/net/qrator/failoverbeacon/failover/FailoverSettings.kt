package net.qrator.failoverbeacon.failover

import java.util.concurrent.TimeUnit

class FailoverSettings(
    val maxAddressAgeNanos: Long = TimeUnit.HOURS.toNanos(1),
    val probeTimeoutMillis: Int = 5_000,
    val maxNetworks: Int = 8
)
