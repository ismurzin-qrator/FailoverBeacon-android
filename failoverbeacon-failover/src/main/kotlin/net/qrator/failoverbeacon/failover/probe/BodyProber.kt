package net.qrator.failoverbeacon.failover.probe

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.failover.model.BodyProbeOutcome
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.NetworkSession
import net.qrator.failoverbeacon.failover.network.NetworkSessions

internal class BodyProber(
    private val sessions: NetworkSessions,
    private val probe: (InetAddress, FailoverHostConfig) -> BodyProbeOutcome,
    private val timer: RefreshTimer,
    private val executor: Executor
) {
    private val running = ConcurrentHashMap.newKeySet<Round>()

    fun probeStaleHosts() {
        sessions.current?.states?.forEach { probeIfStale(it.config.host) }
    }

    fun probeIfStale(host: String) {
        val session = sessions.current ?: return
        val state = session[host] ?: return
        if (state.config.bodyProbe == null || state.addressToBodyProbe(timer) == null) return
        val round = Round(session, state.config)
        if (running.add(round)) executor.execute { run(round) }
    }

    private fun run(round: Round) {
        val session = round.session
        val host = round.config.host
        try {
            while (session.isActive) {
                val address = session[host]?.addressToBodyProbe(timer) ?: return
                val outcome = probe(address, round.config)
                SdkRegistry.logger.debug {
                    "[Failover] $host ${address.hostAddress}: body probe $outcome"
                }
                session.saveBodyProbe(host, address, outcome, timer.now())
            }
        } finally {
            running -= round
        }
    }

    private data class Round(val session: NetworkSession, val config: FailoverHostConfig)
}
