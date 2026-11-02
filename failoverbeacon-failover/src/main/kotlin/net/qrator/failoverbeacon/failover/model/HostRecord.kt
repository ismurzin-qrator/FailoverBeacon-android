package net.qrator.failoverbeacon.failover.model

import java.net.Inet4Address
import java.net.InetAddress

internal class HostRecord private constructor(
    private val resolvedAddresses: List<InetAddress>,
    private val additionalAddresses: List<InetAddress>,
    private val resolvedAtNanos: Long?,
    private val resolveRoundAtNanos: Long?,
    private val probeRoundAtNanos: Long?,
    val hasResolveFailed: Boolean,
    results: Map<InetAddress, ProbeResult>
) {
    constructor(additionalAddresses: List<InetAddress>) :
        this(emptyList(), additionalAddresses, null, null, null, false, emptyMap())

    val addresses: Map<InetAddress, ProbeResult> =
        rank((resolvedAddresses + additionalAddresses).distinct(), results)

    val hasAnswer: Boolean get() = resolvedAtNanos != null

    fun isFresh(nowNanos: Long, intervalNanos: Long): Boolean =
        resolvedAtNanos != null && nowNanos - resolvedAtNanos < intervalNanos

    fun isResolveRoundDue(nowNanos: Long, intervalNanos: Long): Boolean =
        isDue(resolveRoundAtNanos, nowNanos, intervalNanos)

    fun isProbeRoundDue(nowNanos: Long, intervalNanos: Long): Boolean =
        isDue(probeRoundAtNanos, nowNanos, intervalNanos)

    fun withResolved(resolved: List<InetAddress>, nowNanos: Long): HostRecord {
        if (resolved.isEmpty()) return this
        return copy(
            resolvedAddresses = resolved,
            resolvedAtNanos = nowNanos,
            resolveRoundAtNanos = nowNanos,
            hasResolveFailed = false
        )
    }

    fun withResolveFailure(): HostRecord = copy(hasResolveFailed = true)

    fun withResolveRound(nowNanos: Long): HostRecord = copy(resolveRoundAtNanos = nowNanos)

    fun withProbeRound(nowNanos: Long): HostRecord = copy(probeRoundAtNanos = nowNanos)

    fun withProbe(address: InetAddress, result: ProbeResult): HostRecord {
        if (address !in addresses) return this
        return copy(results = addresses + (address to result))
    }

    private fun isDue(roundAtNanos: Long?, nowNanos: Long, intervalNanos: Long): Boolean =
        roundAtNanos == null || nowNanos - roundAtNanos >= intervalNanos

    private fun copy(
        resolvedAddresses: List<InetAddress> = this.resolvedAddresses,
        resolvedAtNanos: Long? = this.resolvedAtNanos,
        resolveRoundAtNanos: Long? = this.resolveRoundAtNanos,
        probeRoundAtNanos: Long? = this.probeRoundAtNanos,
        hasResolveFailed: Boolean = this.hasResolveFailed,
        results: Map<InetAddress, ProbeResult> = addresses
    ) = HostRecord(
        resolvedAddresses,
        additionalAddresses,
        resolvedAtNanos,
        resolveRoundAtNanos,
        probeRoundAtNanos,
        hasResolveFailed,
        results
    )

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
