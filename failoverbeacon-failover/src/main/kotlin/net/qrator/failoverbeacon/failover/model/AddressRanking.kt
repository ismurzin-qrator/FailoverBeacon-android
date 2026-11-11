package net.qrator.failoverbeacon.failover.model

import java.net.Inet4Address

internal object AddressRanking {
    private val byTcp = compareBy<HostAddress>(
        { host ->
            when (val tcp = host.tcp) {
                is LayerResult.Passed -> if (tcp.nanos != null) 0 else 1
                LayerResult.Unknown -> 2
                LayerResult.Failed -> 3
            }
        },
        { host -> (host.tcp as? LayerResult.Passed)?.nanos }
    )
    private val ipv4Order = compareBy<HostAddress>(
        { it.hasFailure },
        { it.body !is LayerResult.Passed }
    ).then(byTcp)

    // Ranks IPv4 addresses by health (passed body probe first, then TCP latency),
    // while preserving IPv6 order. Failed addresses on any layer are moved to the end.
    fun rank(addresses: List<HostAddress>): List<HostAddress> {
        val (failedIpv6, kept) = addresses.partition {
            it.address !is Inet4Address && it.hasFailure
        }
        val rankedIpv4 = kept
            .filter { it.address is Inet4Address }
            .sortedWith(ipv4Order)
            .iterator()
        return kept.map { if (it.address is Inet4Address) rankedIpv4.next() else it } + failedIpv6
    }
}
