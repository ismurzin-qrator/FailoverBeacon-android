package net.qrator.failoverbeacon.failover.mapper

import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.time.EpochClock
import net.qrator.failoverbeacon.failover.model.DnsState
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.HostAddress
import net.qrator.failoverbeacon.failover.model.HostState
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.model.SavedAddress
import net.qrator.failoverbeacon.failover.model.SavedBodyProbe
import net.qrator.failoverbeacon.failover.model.SavedHost

internal class SavedHostMapper(
    hostConfigs: List<FailoverHostConfig>,
    clock: EpochClock,
    private val bodyProbeTimer: RefreshTimer
) {
    private val configs = hostConfigs.associateBy { it.host }
    private val answerTimer = RefreshTimer(clock, MAX_ANSWER_AGE_MILLIS)

    fun toSaved(state: HostState): SavedHost? {
        val addresses = state.addresses.mapNotNull { toSaved(it, state.dns) }
        if (addresses.isEmpty()) return null
        return SavedHost(state.config.host, state.dns.answeredAtMillis, addresses)
    }

    fun toState(saved: SavedHost): HostState? {
        val config = configs[saved.host] ?: return null
        val answeredAt = saved.answeredAtMillis
        if (answeredAt != null && !answerTimer.accepts(answeredAt)) return null
        val answer = saved.addresses.filter { it.isFromDns }.map { it.address }
        val dns = answeredAt?.let { DnsState().withAnswer(answer, it) } ?: DnsState()
        return HostState(config).withDns(dns, saved.addresses.map(::toAddress))
    }

    private fun toSaved(address: HostAddress, dns: DnsState): SavedAddress? {
        val isFromDns = address.address in dns.addresses
        val tcpNanos = (address.tcp as? LayerResult.Passed)?.nanos
        val bodyProbe = toSavedBodyProbe(address)
        if (!isFromDns && tcpNanos == null && bodyProbe == null) return null
        return SavedAddress(address.address, isFromDns, tcpNanos, bodyProbe)
    }

    private fun toSavedBodyProbe(address: HostAddress): SavedBodyProbe? {
        val atMillis = address.bodyProbedAtMillis ?: return null
        return when {
            address.body == LayerResult.Failed -> SavedBodyProbe(false, atMillis)

            address.body is LayerResult.Passed && !address.hasFailure ->
                SavedBodyProbe(true, atMillis)

            else -> null
        }
    }

    private fun toAddress(saved: SavedAddress): HostAddress {
        val tcp = saved.tcpNanos?.let { LayerResult.Passed(it) } ?: LayerResult.Unknown
        val bodyProbe = saved.bodyProbe?.takeIf { bodyProbeTimer.accepts(it.atMillis) }
            ?: return HostAddress(saved.address, tcp)
        val body = if (bodyProbe.isPassed) LayerResult.Passed(nanos = null) else LayerResult.Failed
        return HostAddress(saved.address, tcp, body = body, bodyProbedAtMillis = bodyProbe.atMillis)
    }

    private fun RefreshTimer.accepts(atMillis: Long): Boolean =
        atMillis <= now() && !isExpired(atMillis)

    private companion object {
        val MAX_ANSWER_AGE_MILLIS = TimeUnit.DAYS.toMillis(7)
    }
}
