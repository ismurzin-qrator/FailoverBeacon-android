package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

internal class HostState private constructor(
    val config: FailoverHostConfig,
    val dns: DnsState,
    private val probeRoundAtNanos: Long?,
    knownAddresses: List<HostAddress>
) {
    constructor(config: FailoverHostConfig) : this(config, DnsState(), null, emptyList())

    val addresses: List<HostAddress> =
        AddressRanking.rank((dns.addresses + config.additionalAddresses).distinct(), knownAddresses)

    fun isProbeStale(timer: RefreshTimer): Boolean =
        probeRoundAtNanos == null || timer.isExpired(probeRoundAtNanos)

    fun withDns(dns: DnsState): HostState = HostState(config, dns, probeRoundAtNanos, addresses)

    fun withProbeRound(atNanos: Long): HostState = HostState(config, dns, atNanos, addresses)

    fun withProbe(address: InetAddress, result: ProbeResult): HostState {
        if (addresses.none { it.address == address }) return this
        val updated = addresses.map {
            if (it.address == address) HostAddress(address, result) else it
        }
        return HostState(config, dns, probeRoundAtNanos, updated)
    }
}
