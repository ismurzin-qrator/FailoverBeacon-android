package net.qrator.failoverbeacon.failover.probe

import java.net.InetAddress
import java.util.concurrent.Executor
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.network.NetworkSessions

internal class TcpProber(
    private val sessions: NetworkSessions,
    connect: (InetAddress, Int) -> Long?,
    executor: Executor,
    bodyProber: BodyProber
) {
    private val tasks = TcpProbeTasks(connect, executor, bodyProber)

    fun probeStaleHosts() {
        sessions.current?.states?.forEach { probeIfStale(it.config.host) }
    }

    fun probeIfStale(host: String) {
        val session = sessions.current ?: return
        val state = session[host] ?: return
        if (!state.config.tcpProbe || !state.isRankable) return
        val roundAtMillis = session.startTcpProbeRoundIfStale(host)
        state.ipv4Addresses
            .filter { roundAtMillis != null || it.tcp == LayerResult.Unknown }
            .forEach { tasks.start(session, state.config, it.address, roundAtMillis) }
    }
}
