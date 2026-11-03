package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

internal class HostState private constructor(
    val config: FailoverHostConfig,
    val dns: DnsState,
    private val probeRoundAtNanos: Long?,
    val addresses: List<HostAddress>
) {
    constructor(config: FailoverHostConfig) : this(
        config,
        DnsState(),
        null,
        config.additionalAddresses.map { HostAddress(it) }
    )

    fun isProbeStale(timer: RefreshTimer): Boolean =
        probeRoundAtNanos == null || timer.isExpired(probeRoundAtNanos)

    fun withDns(dns: DnsState): HostState {
        val updatedAddresses = (dns.addresses + config.additionalAddresses).distinct()
        val updated = updatedAddresses.map { address ->
            addresses.find { it.address == address } ?: HostAddress(address)
        }
        val updatedAddressesRanked = AddressRanking.rank(updated)
        return HostState(config, dns, probeRoundAtNanos, updatedAddressesRanked)
    }

    fun withProbeRound(atNanos: Long): HostState = HostState(config, dns, atNanos, addresses)

    fun withProbe(address: InetAddress, result: LayerResult): HostState {
        val updated = addresses.map { if (it.address == address) it.copy(tcp = result) else it }
        return HostState(config, dns, probeRoundAtNanos, AddressRanking.rank(updated))
    }

    fun withConnectOutcome(peerAddress: String, outcome: ConnectOutcome): HostState {
        val updated = addresses.map {
            if (it.address.hostAddress == peerAddress) it.withConnectOutcome(outcome) else it
        }
        return HostState(config, dns, probeRoundAtNanos, AddressRanking.rank(updated))
    }
}
