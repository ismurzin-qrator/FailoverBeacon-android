package net.qrator.failoverbeacon.failover.traffic

import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ProxyType
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot
import net.qrator.failoverbeacon.failover.mapper.ConnectOutcomeMapper
import net.qrator.failoverbeacon.failover.network.NetworkSessions

internal class TrafficFeedback(private val sessions: NetworkSessions) {
    private val outcomeMapper = ConnectOutcomeMapper()

    fun save(exchange: Exchange) {
        val host = exchange.request.serverAddress ?: return
        val session = sessions.current ?: return
        val network = exchange.network as? NetworkSnapshot.Connected ?: return
        if (network.handle != session.networkHandle) return
        val isSecure = exchange.request.scheme == HTTPS
        for (attempt in exchange.attempts) {
            if (attempt.proxy != ProxyType.DIRECT) continue
            val outcome = outcomeMapper.toOutcome(attempt.result, isSecure) ?: continue
            session.saveConnectOutcome(host, attempt.peerAddress, outcome)
        }
    }

    private companion object {
        const val HTTPS = "https"
    }
}
