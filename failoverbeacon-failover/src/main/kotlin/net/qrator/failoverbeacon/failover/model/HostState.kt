package net.qrator.failoverbeacon.failover.model

import java.net.Inet4Address
import java.net.InetAddress

internal class HostState private constructor(
    val config: FailoverHostConfig,
    val dns: DnsState,
    private val tcpProbeRoundAtMillis: Long?,
    val addresses: List<HostAddress>
) {
    constructor(config: FailoverHostConfig) : this(
        config,
        DnsState(),
        null,
        config.additionalAddresses.map { HostAddress(it) }
    )

    fun isTcpProbeStale(timer: RefreshTimer): Boolean =
        tcpProbeRoundAtMillis == null || timer.isExpired(tcpProbeRoundAtMillis)

    // The leader, once TCP results are in.
    fun addressToBodyProbe(timer: RefreshTimer): InetAddress? {
        val ipv4Addresses = addresses.filter { it.address is Inet4Address }
        val leader = ipv4Addresses.firstOrNull() ?: return null
        val isDue = ipv4Addresses.size > 1 &&
            ipv4Addresses.none { it.tcp == LayerResult.Unknown } &&
            leader.tcp != LayerResult.Failed &&
            leader.isBodyProbeStale(timer) &&
            ipv4Addresses.count { !it.isBodyProbeStale(timer) } < MAX_FRESH_BODY_PROBES
        return leader.address.takeIf { isDue }
    }

    fun withDns(dns: DnsState, known: List<HostAddress> = addresses): HostState {
        val updatedAddresses = (dns.addresses + config.additionalAddresses).distinct()
        val updated = updatedAddresses.map { address ->
            known.find { it.address == address } ?: HostAddress(address)
        }
        val updatedAddressesRanked = AddressRanking.rank(updated)
        return HostState(config, dns, tcpProbeRoundAtMillis, updatedAddressesRanked)
    }

    fun withTcpProbeRound(atMillis: Long): HostState = HostState(config, dns, atMillis, addresses)

    fun withTcpProbe(address: InetAddress, result: LayerResult): HostState {
        val updated = addresses.map { if (it.address == address) it.copy(tcp = result) else it }
        return HostState(config, dns, tcpProbeRoundAtMillis, AddressRanking.rank(updated))
    }

    fun withConnectOutcome(peerAddress: String, outcome: ConnectOutcome): HostState {
        val updated = addresses.map {
            if (it.address.hostAddress == peerAddress) it.withConnectOutcome(outcome) else it
        }
        return HostState(config, dns, tcpProbeRoundAtMillis, AddressRanking.rank(updated))
    }

    fun withBodyProbeOutcome(
        address: InetAddress,
        outcome: BodyProbeOutcome,
        atMillis: Long
    ): HostState {
        val updated = addresses.map {
            if (it.address == address) it.withBodyProbeOutcome(outcome, atMillis) else it
        }
        return HostState(config, dns, tcpProbeRoundAtMillis, AddressRanking.rank(updated))
    }

    private companion object {
        const val MAX_FRESH_BODY_PROBES = 3
    }
}
