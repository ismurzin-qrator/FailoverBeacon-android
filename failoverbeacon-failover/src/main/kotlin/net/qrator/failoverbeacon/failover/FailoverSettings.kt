package net.qrator.failoverbeacon.failover

class FailoverSettings(
    val preresolveOnNetworkChange: Boolean,
    val probeOnNetworkChange: Boolean,
    val refreshIntervalNanos: Long
)
