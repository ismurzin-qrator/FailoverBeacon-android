package net.qrator.failoverbeacon.failover.probe

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.failover.model.BodyProbeConfig
import net.qrator.failoverbeacon.failover.model.BodyProbeOutcome
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.MaxAge
import net.qrator.failoverbeacon.failover.network.NetworkSession
import net.qrator.failoverbeacon.failover.network.NetworkSessions

internal class BodyProber(
    private val sessions: NetworkSessions,
    private val probe: (InetAddress, String, Int, BodyProbeConfig) -> BodyProbeOutcome,
    private val maxAge: MaxAge,
    private val executor: Executor
) {
    private val running = ConcurrentHashMap.newKeySet<Round>()

    fun probeStaleHosts() {
        sessions.current?.states?.forEach { probeIfStale(it.config.host) }
    }

    fun probeIfStale(host: String) {
        val session = sessions.current ?: return
        val state = session[host] ?: return
        val settings = state.config.bodyProbe ?: return
        if (state.addressToBodyProbe(maxAge) == null) return
        val round = Round(session, state.config, settings)
        if (running.add(round)) executor.execute { run(round) }
    }

    private fun run(round: Round) {
        val session = round.session
        val host = round.config.host
        try {
            while (session.isActive) {
                val address = session[host]?.addressToBodyProbe(maxAge) ?: return
                val outcome = probe(address, host, round.config.port, round.settings)
                SdkRegistry.logger.debug {
                    "[Failover] $host ${address.hostAddress}: body probe $outcome"
                }
                session.saveBodyProbe(host, address, outcome, maxAge.now())
            }
        } finally {
            running -= round
        }
    }

    private data class Round(
        val session: NetworkSession,
        val config: FailoverHostConfig,
        val settings: BodyProbeConfig
    )
}
