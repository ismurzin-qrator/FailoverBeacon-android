package net.qrator.failoverbeacon.failover.probe

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.network.NetworkSessions

internal class TcpProber(
    private val sessions: NetworkSessions,
    connect: (InetAddress, Int) -> LayerResult,
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
        if (!state.config.tcpProbe) return
        val ipv4Addresses = state.addresses.filter { it.address is Inet4Address }
        if (ipv4Addresses.size < 2) return
        val roundAtMillis = session.startTcpProbeRoundIfStale(host)
        ipv4Addresses
            .filter { roundAtMillis != null || it.tcp == LayerResult.Unknown }
            .forEach { tasks.start(session, state.config, it.address, roundAtMillis) }
    }
}
