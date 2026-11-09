package net.qrator.failoverbeacon.failover.network

import java.net.InetAddress
import net.qrator.failoverbeacon.failover.model.BodyProbeOutcome
import net.qrator.failoverbeacon.failover.model.ConnectOutcome
import net.qrator.failoverbeacon.failover.model.HostState
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.model.RefreshTimer

internal class NetworkSession(
    private val state: NetworkState,
    private val timer: RefreshTimer,
    val networkHandle: Long
) {
    @Volatile
    var isActive: Boolean = true
        private set

    val states: List<HostState> get() = state.hosts

    operator fun get(host: String): HostState? = state[host]

    fun saveAnswer(host: String, addresses: List<InetAddress>) = updateIfActive(host) { state ->
        state.withDns(state.dns.withAnswer(addresses, timer.now()))
    }

    fun saveResolveFailure(host: String) = updateIfActive(host) { state ->
        state.withDns(state.dns.withFailure(timer.now()))
    }

    fun saveTcpProbe(host: String, address: InetAddress, result: LayerResult) =
        updateIfActive(host) { state -> state.withTcpProbe(address, result) }

    fun saveConnectOutcome(host: String, peerAddress: String, outcome: ConnectOutcome) =
        updateIfActive(host) { state -> state.withConnectOutcome(peerAddress, outcome) }

    fun saveBodyProbe(
        host: String,
        address: InetAddress,
        outcome: BodyProbeOutcome,
        atMillis: Long
    ) = updateIfActive(host) { state -> state.withBodyProbeOutcome(address, outcome, atMillis) }

    fun startTcpProbeRoundIfStale(host: String): Long? {
        var roundAtMillis: Long? = null
        updateIfActive(host) { state ->
            if (state.isTcpProbeStale(timer)) {
                val now = timer.now()
                roundAtMillis = now
                state.withTcpProbeRound(now)
            } else {
                state
            }
        }
        return roundAtMillis
    }

    fun cancelTcpProbeRound(host: String, roundAtMillis: Long) =
        state.update(host) { it.withoutTcpProbeRound(roundAtMillis) }

    fun end() {
        isActive = false
    }

    private fun updateIfActive(host: String, change: (HostState) -> HostState) {
        if (isActive) state.update(host, change)
    }
}
