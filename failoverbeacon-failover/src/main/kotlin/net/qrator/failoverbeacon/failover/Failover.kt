package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import net.qrator.failoverbeacon.core.SdkFeature
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.call.ExchangeSubscriber
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.network.model.DefaultNetwork
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.NetworkSessions
import net.qrator.failoverbeacon.failover.probe.AddressProber
import net.qrator.failoverbeacon.failover.probe.ProbeTasks
import net.qrator.failoverbeacon.failover.probe.TcpProbe
import net.qrator.failoverbeacon.failover.resolve.AddressResolver
import net.qrator.failoverbeacon.failover.resolve.ResolveTasks
import net.qrator.failoverbeacon.failover.traffic.TrafficFeedback

class Failover(
    private val sdk: SdkInstance,
    hostConfigs: List<FailoverHostConfig>,
    queryDns: (String) -> List<InetAddress>,
    settings: FailoverSettings
) : SdkFeature,
    ExchangeSubscriber {
    private val timer = RefreshTimer(sdk.elapsedRealtimeClock, settings.refreshIntervalNanos)
    private val sessions = NetworkSessions(hostConfigs, timer, MAX_NETWORKS)
    private val feedback = TrafficFeedback(sessions)
    private val tcpProbe = TcpProbe(sdk.clock, PROBE_TIMEOUT_MILLIS)
    private val prober = AddressProber(
        sessions = sessions,
        tasks = ProbeTasks(tcpProbe::connect, sdk.threads.failoverProbe),
        probeOnNetworkChange = settings.probeOnNetworkChange
    )
    private val resolveTasks = ResolveTasks(
        queryDns = queryDns,
        executor = sdk.threads.failoverResolve,
        onAnswer = prober::probeIfStale
    )
    private val resolver = AddressResolver(
        sessions = sessions,
        tasks = resolveTasks,
        timer = timer,
        preresolveOnNetworkChange = settings.preresolveOnNetworkChange,
        waitMillis = RESOLVE_WAIT_MILLIS
    )

    fun subscribe() {
        sdk.dns = FailoverDns(sessions, prober, resolver, timer)
        sdk.exchangeBus.subscribe(this)
        sdk.addFeature(this)
    }

    override fun onNetworkChanged(network: DefaultNetwork?) {
        val usableNetwork = network?.takeIf { it.blocked != true }
        sessions.switchTo(usableNetwork)
        if (usableNetwork != null) {
            prober.probeStaleHosts()
            resolver.preresolve()
        }
    }

    override fun onExchange(exchange: Exchange) {
        feedback.save(exchange)
        prober.probeStaleHosts()
        resolver.resolveStaleHosts()
    }

    override fun close() {
        tcpProbe.close()
        resolveTasks.close()
    }

    private companion object {
        const val MAX_NETWORKS = 8
        const val PROBE_TIMEOUT_MILLIS = 5_000
        const val RESOLVE_WAIT_MILLIS = 2_000L
    }
}
