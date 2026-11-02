package net.qrator.failoverbeacon.failover

class FailoverSettings(
    val preresolveOnNetworkChange: Boolean,
    val probeOnNetworkChange: Boolean,
    val refreshIntervalNanos: Long,
    val resolveWaitMillis: Long = 2_000,
    val probeTimeoutMillis: Int = 5_000,
    val maxNetworks: Int = 8
)
