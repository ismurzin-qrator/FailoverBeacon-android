package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import net.qrator.failoverbeacon.core.SdkFeature
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.call.ExchangeSubscriber
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.network.model.DefaultNetwork
import net.qrator.failoverbeacon.failover.mapper.SavedHostMapper
import net.qrator.failoverbeacon.failover.model.BodyProbeOutcome
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.KnownNetworks
import net.qrator.failoverbeacon.failover.network.NetworkSessions
import net.qrator.failoverbeacon.failover.probe.BodyProbe
import net.qrator.failoverbeacon.failover.probe.BodyProber
import net.qrator.failoverbeacon.failover.probe.TcpProbe
import net.qrator.failoverbeacon.failover.probe.TcpProbeTasks
import net.qrator.failoverbeacon.failover.probe.TcpProber
import net.qrator.failoverbeacon.failover.resolve.AddressResolver
import net.qrator.failoverbeacon.failover.resolve.ResolveTasks
import net.qrator.failoverbeacon.failover.store.KnownNetworksPersistence
import net.qrator.failoverbeacon.failover.traffic.TrafficFeedback

class Failover(
    private val sdk: SdkInstance,
    hostConfigs: List<FailoverHostConfig>,
    queryDns: (String) -> List<InetAddress>,
    settings: FailoverSettings
) : SdkFeature,
    ExchangeSubscriber {
    private val timer = RefreshTimer(sdk.epochClock, settings.refreshIntervalMillis)
    private val bodyProbeTimer = RefreshTimer(sdk.epochClock, BODY_PROBE_INTERVAL_MILLIS)
    private val networks = KnownNetworks(hostConfigs, MAX_NETWORKS)
    private val sessions = NetworkSessions(networks, timer)
    private val persistence = KnownNetworksPersistence(
        cacheDir = sdk.appContext::getCacheDir,
        networks = networks,
        hostConfigs = hostConfigs,
        clock = sdk.epochClock,
        bodyProbeTimer = bodyProbeTimer,
        storage = sdk.threads.storage,
        scheduler = sdk.threads.upload
    )
    private val feedback = TrafficFeedback(sessions)
    private val tcpProbe = TcpProbe(sdk.clock, PROBE_TIMEOUT_MILLIS)
    private val bodyProbe = BodyProbe(
        clock = sdk.clock,
        socketFactory = { SSLSocketFactory.getDefault() as SSLSocketFactory },
        hostnameVerifier = { host, session ->
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
        },
        timeoutMillis = PROBE_TIMEOUT_MILLIS,
        deadlineMillis = BODY_PROBE_DEADLINE_MILLIS
    )
    private val bodyProber = BodyProber(
        sessions = sessions,
        probe = ::runBodyProbe,
        timer = bodyProbeTimer,
        executor = sdk.threads.failoverProbe,
        probeOnNetworkChange = settings.probeOnNetworkChange
    )
    private val tcpProber = TcpProber(
        sessions = sessions,
        tasks = TcpProbeTasks(tcpProbe::run, sdk.threads.failoverProbe, bodyProber::probeIfStale),
        probeOnNetworkChange = settings.probeOnNetworkChange
    )
    private val resolveTasks = ResolveTasks(
        queryDns = queryDns,
        executor = sdk.threads.failoverResolve,
        onAnswer = tcpProber::probeIfStale
    )
    private val resolver = AddressResolver(
        sessions = sessions,
        tasks = resolveTasks,
        timer = timer,
        preresolveOnNetworkChange = settings.preresolveOnNetworkChange,
        waitMillis = RESOLVE_WAIT_MILLIS
    )

    fun subscribe() {
        sdk.dns = FailoverDns(sessions, tcpProber, resolver, timer)
        sdk.exchangeBus.subscribe(this)
        sdk.addFeature(this)
    }

    fun start() = persistence.restore()

    override fun onNetworkChanged(network: DefaultNetwork?) =
        sdk.threads.storage.execute { startSession(network) }

    override fun onExchange(exchange: Exchange) {
        feedback.save(exchange)
        tcpProber.probeStaleHosts()
        bodyProber.probeStaleHosts()
        resolver.resolveStaleHosts()
        persistence.saveLater()
    }

    override fun clearData() {
        persistence.clear()
        sdk.threads.storage.execute { sessions.switchTo(sessions.network) }
    }

    override fun close() {
        tcpProbe.close()
        bodyProbe.close()
        resolveTasks.close()
    }

    private fun startSession(network: DefaultNetwork?) {
        val usableNetwork = network?.takeIf { it.blocked != true }
        sessions.switchTo(usableNetwork)
        if (usableNetwork != null) {
            tcpProber.probeStaleHosts()
            bodyProber.probeStaleHosts()
            resolver.preresolve()
            persistence.saveLater()
        }
    }

    private fun runBodyProbe(address: InetAddress, config: FailoverHostConfig): BodyProbeOutcome {
        val settings = checkNotNull(config.bodyProbe)
        return bodyProbe.run(address, config.host, config.port, settings.path, settings.maxBytes)
    }

    private companion object {
        const val MAX_NETWORKS = 8
        const val PROBE_TIMEOUT_MILLIS = 5_000
        const val BODY_PROBE_DEADLINE_MILLIS = 15_000L
        val BODY_PROBE_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1)
        const val RESOLVE_WAIT_MILLIS = 2_000L
    }
}
