package net.qrator.failoverbeacon.failover.network

import java.net.InetAddress
import net.qrator.failoverbeacon.failover.model.BodyProbeOutcome
import net.qrator.failoverbeacon.failover.model.ConnectOutcome
import net.qrator.failoverbeacon.failover.model.HostState
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.model.MaxAge

internal class NetworkSession(
    private val hosts: NetworkHosts,
    private val maxAge: MaxAge,
    val networkHandle: Long
) {
    @Volatile
    var isActive: Boolean = true
        private set

    val states: List<HostState> get() = hosts.all

    operator fun get(host: String): HostState? = hosts[host]

    fun saveAnswer(host: String, addresses: List<InetAddress>) = updateIfActive(host) { state ->
        state.withDns(state.dns.withAnswer(addresses, maxAge.now()))
    }

    fun saveResolveFailure(host: String) = updateIfActive(host) { state ->
        state.withDns(state.dns.withFailure(maxAge.now()))
    }

    fun saveTcpProbe(host: String, address: InetAddress, result: LayerResult) =
        updateIfActive(host) { state -> state.withTcpProbe(address, result) }

    fun saveConnectOutcome(host: String, peerAddress: String, outcome: ConnectOutcome) =
        updateIfActive(host) { state -> state.withConnectOutcome(peerAddress, outcome) }

    fun saveResponse(host: String, peerAddress: String, isAnswered: Boolean) =
        updateIfActive(host) { state -> state.withResponse(peerAddress, isAnswered, maxAge.now()) }

    fun saveBodyProbe(
        host: String,
        address: InetAddress,
        outcome: BodyProbeOutcome,
        atMillis: Long
    ) = updateIfActive(host) { state -> state.withBodyProbeOutcome(address, outcome, atMillis) }

    fun startTcpProbeRoundIfStale(host: String): Long? {
        var roundAtMillis: Long? = null
        updateIfActive(host) { state ->
            if (state.isTcpProbeStale(maxAge)) {
                val now = maxAge.now()
                roundAtMillis = now
                state.withTcpProbeRound(now)
            } else {
                state
            }
        }
        return roundAtMillis
    }

    fun cancelTcpProbeRound(host: String, roundAtMillis: Long) =
        hosts.update(host) { it.withoutTcpProbeRound(roundAtMillis) }

    fun end() {
        isActive = false
    }

    private fun updateIfActive(host: String, change: (HostState) -> HostState) {
        if (isActive) hosts.update(host, change)
    }
}
