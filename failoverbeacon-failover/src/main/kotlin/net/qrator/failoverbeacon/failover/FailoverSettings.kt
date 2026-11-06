package net.qrator.failoverbeacon.failover

class FailoverSettings(
    val preresolveOnNetworkChange: Boolean,
    val probeOnNetworkChange: Boolean,
    val refreshIntervalMillis: Long,
    val fallbackWithoutEch: Boolean
)
