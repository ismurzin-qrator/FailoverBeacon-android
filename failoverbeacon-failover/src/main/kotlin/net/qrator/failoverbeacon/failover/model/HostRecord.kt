package net.qrator.failoverbeacon.failover.model

import java.net.Inet4Address
import java.net.InetAddress

internal class HostRecord private constructor(
    private val resolvedAddresses: List<InetAddress>,
    private val additionalAddresses: List<InetAddress>,
    private val resolvedAtNanos: Long?,
    results: Map<InetAddress, ProbeResult>
) {
    constructor(additionalAddresses: List<InetAddress>) :
        this(emptyList(), additionalAddresses, null, emptyMap())

    val addresses: Map<InetAddress, ProbeResult> =
        rank((resolvedAddresses + additionalAddresses).distinct(), results)

    fun isFresh(nowNanos: Long, maxAgeNanos: Long): Boolean =
        resolvedAtNanos != null && nowNanos - resolvedAtNanos <= maxAgeNanos

    fun withResolved(resolved: List<InetAddress>, nowNanos: Long): HostRecord {
        if (resolved.isEmpty()) return this
        return HostRecord(resolved, additionalAddresses, nowNanos, addresses)
    }

    fun withoutProbes(): HostRecord =
        HostRecord(resolvedAddresses, additionalAddresses, resolvedAtNanos, emptyMap())

    fun withProbe(address: InetAddress, result: ProbeResult): HostRecord {
        if (address !in addresses) return this
        return HostRecord(
            resolvedAddresses,
            additionalAddresses,
            resolvedAtNanos,
            addresses + (address to result)
        )
    }

    private fun rank(
        source: List<InetAddress>,
        results: Map<InetAddress, ProbeResult>
    ): Map<InetAddress, ProbeResult> {
        val rankedIpv4 = source
            .filterIsInstance<Inet4Address>()
            .sortedWith(
                compareBy(ProbeResult.fastestFirst) {
                    results[it] ?: ProbeResult.Unknown
                }
            )
            .iterator()
        return source
            .map { if (it is Inet4Address) rankedIpv4.next() else it }
            .associateWith { results[it] ?: ProbeResult.Unknown }
    }
}
