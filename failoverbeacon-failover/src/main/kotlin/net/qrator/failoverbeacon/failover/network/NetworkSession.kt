package net.qrator.failoverbeacon.failover.network

import java.net.InetAddress
import net.qrator.failoverbeacon.failover.model.HostState
import net.qrator.failoverbeacon.failover.model.ProbeResult
import net.qrator.failoverbeacon.failover.model.RefreshTimer

internal class NetworkSession(
    val isFirstNetwork: Boolean,
    private val hostStates: HostStates,
    private val timer: RefreshTimer
) {
    @Volatile
    var isActive: Boolean = true
        private set

    val states: List<HostState> get() = hostStates.all

    operator fun get(host: String): HostState? = hostStates[host]

    fun saveAnswer(host: String, addresses: List<InetAddress>) = updateIfActive(host) { state ->
        state.withDns(state.dns.withAnswer(addresses, timer.now()))
    }

    fun saveResolveFailure(host: String) = updateIfActive(host) { state ->
        state.withDns(state.dns.withFailure(timer.now()))
    }

    fun saveProbe(host: String, address: InetAddress, result: ProbeResult) =
        updateIfActive(host) { state -> state.withProbe(address, result) }

    fun startProbeRoundIfStale(host: String): Boolean {
        var isStarted = false
        updateIfActive(host) { state ->
            isStarted = state.isProbeStale(timer)
            if (isStarted) state.withProbeRound(timer.now()) else state
        }
        return isStarted
    }

    fun end() {
        isActive = false
    }

    private fun updateIfActive(host: String, change: (HostState) -> HostState) {
        if (isActive) hostStates.update(host, change)
    }
}
