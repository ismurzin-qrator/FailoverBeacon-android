package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import net.qrator.failoverbeacon.core.dns.SdkDns
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.NetworkSessions
import net.qrator.failoverbeacon.failover.probe.AddressProber
import net.qrator.failoverbeacon.failover.resolve.AddressResolver

internal class FailoverDns(
    private val sessions: NetworkSessions,
    private val prober: AddressProber,
    private val resolver: AddressResolver,
    private val timer: RefreshTimer
) : SdkDns {
    override fun manages(host: String): Boolean = sessions.current?.get(host) != null

    override fun lookup(host: String): List<InetAddress>? {
        val state = sessions.current?.get(host) ?: return null
        prober.probeStaleHosts()
        resolver.resolveStaleHosts()
        if (state.dns.isAnswerNeeded(timer)) {
            resolver.resolveAndWait(host)
        } else {
            resolver.resolveInBackground(host)
        }
        return sessions.current?.get(host)?.addresses?.map { it.address }?.ifEmpty { null }
    }
}
