package net.qrator.failoverbeacon.failover.model

import java.net.Inet4Address
import java.net.InetAddress

internal object AddressRanking {
    private val fastestFirst = compareBy<HostAddress>(
        { host ->
            when (host.probe) {
                is ProbeResult.Connected -> 0
                ProbeResult.Unknown -> 1
                ProbeResult.Failed -> 2
            }
        },
        { host -> (host.probe as? ProbeResult.Connected)?.connectNanos }
    )

    // IPv4: by connect time, then unknown, then failed. IPv6 keeps its places.
    fun rank(source: List<InetAddress>, known: List<HostAddress>): List<HostAddress> {
        val candidates = source.map { address ->
            known.find { it.address == address } ?: HostAddress(address, ProbeResult.Unknown)
        }
        val rankedIpv4 = candidates
            .filter { it.address is Inet4Address }
            .sortedWith(fastestFirst)
            .iterator()
        return candidates.map { if (it.address is Inet4Address) rankedIpv4.next() else it }
    }
}
