package net.qrator.failoverbeacon.failover.probe

import java.net.Inet4Address
import java.net.InetAddress
import net.qrator.failoverbeacon.failover.model.HostState
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.network.NetworkSession
import net.qrator.failoverbeacon.failover.network.NetworkSessions

internal class TcpProber(
    private val sessions: NetworkSessions,
    private val tasks: TcpProbeTasks,
    private val probeOnNetworkChange: Boolean
) {
    fun probeStaleHosts() {
        sessions.current?.states?.forEach { probeIfStale(it.config.host) }
    }

    fun probeIfStale(host: String) {
        val session = sessions.current ?: return
        val state = session[host] ?: return
        if (!state.config.tcpProbe) return
        if (!probeOnNetworkChange && !session.isFirstNetwork) return
        addressesToProbe(session, state).forEach { tasks.start(session, state.config, it) }
    }

    private fun addressesToProbe(session: NetworkSession, state: HostState): List<InetAddress> {
        val ipv4Addresses = state.addresses.filter { it.address is Inet4Address }
        if (ipv4Addresses.size < 2) return emptyList()
        val isRoundStarted = session.startTcpProbeRoundIfStale(state.config.host)
        return ipv4Addresses
            .filter { isRoundStarted || it.tcp == LayerResult.Unknown }
            .map { it.address }
    }
}
