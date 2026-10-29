package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

internal class HostRecord private constructor(
    val host: String,
    val additionalAddresses: List<InetAddress>,
    val addresses: Map<InetAddress, ProbeResult>,
    private val resolvedAtNanos: Long?
) {
    constructor(host: String, additionalAddresses: List<InetAddress>) : this(
        host,
        additionalAddresses,
        additionalAddresses.associateWith { ProbeResult.Unknown },
        null
    )

    fun isFresh(nowNanos: Long, maxAgeNanos: Long): Boolean =
        resolvedAtNanos != null && nowNanos - resolvedAtNanos <= maxAgeNanos

    fun withResolved(resolved: List<InetAddress>, nowNanos: Long): HostRecord {
        if (resolved.isEmpty()) return this
        val updated = (resolved + additionalAddresses).associateWith {
            addresses[it] ?: ProbeResult.Unknown
        }
        return HostRecord(host, additionalAddresses, updated, nowNanos)
    }

    fun withProbe(address: InetAddress, result: ProbeResult): HostRecord {
        if (address !in addresses) return this
        return HostRecord(
            host,
            additionalAddresses,
            addresses + (address to result),
            resolvedAtNanos
        )
    }
}
