package net.qrator.failoverbeacon.failover.model

import java.net.Inet4Address

internal object AddressRanking {
    private val fastestFirst = compareBy<HostAddress>(
        { host -> host.hasFailure },
        { host ->
            when (val tcp = host.tcp) {
                is LayerResult.Passed -> if (tcp.nanos != null) 0 else 1
                LayerResult.Unknown -> 2
                LayerResult.Failed -> 3
            }
        },
        { host -> (host.tcp as? LayerResult.Passed)?.nanos }
    )

    // IPv4: failed on any layer go last. Within each group: by probe time, then passed without
    // a time, then unknown, then failed on TCP. IPv6 keeps its places, failed IPv6 goes last.
    fun rank(addresses: List<HostAddress>): List<HostAddress> {
        val (failedIpv6, kept) = addresses.partition {
            it.address !is Inet4Address && it.hasFailure
        }
        val rankedIpv4 = kept
            .filter { it.address is Inet4Address }
            .sortedWith(fastestFirst)
            .iterator()
        return kept.map { if (it.address is Inet4Address) rankedIpv4.next() else it } + failedIpv6
    }
}
