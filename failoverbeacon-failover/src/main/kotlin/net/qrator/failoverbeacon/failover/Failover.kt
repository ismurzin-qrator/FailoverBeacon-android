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
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.network.KnownNetworks
import net.qrator.failoverbeacon.failover.network.NetworkSessions
import net.qrator.failoverbeacon.failover.probe.BodyProbe
import net.qrator.failoverbeacon.failover.probe.BodyProber
import net.qrator.failoverbeacon.failover.probe.TcpProbe
import net.qrator.failoverbeacon.failover.probe.TcpProber
import net.qrator.failoverbeacon.failover.resolve.AddressResolver
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
    private val fallbackWithoutEch = settings.fallbackWithoutEch
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
        probe = bodyProbe::run,
        timer = bodyProbeTimer,
        executor = sdk.threads.failoverProbe,
        probeOnNetworkChange = settings.probeOnNetworkChange
    )
    private val tcpProber = TcpProber(
        sessions = sessions,
        connect = tcpProbe::run,
        executor = sdk.threads.failoverProbe,
        bodyProber = bodyProber,
        probeOnNetworkChange = settings.probeOnNetworkChange
    )
    private val resolver = AddressResolver(
        sessions = sessions,
        queryDns = queryDns,
        executor = sdk.threads.failoverResolve,
        tcpProber = tcpProber,
        timer = timer,
        preresolveOnNetworkChange = settings.preresolveOnNetworkChange,
        waitMillis = RESOLVE_WAIT_MILLIS
    )

    fun subscribe() {
        sdk.dns = FailoverDns(sessions, tcpProber, resolver, timer, fallbackWithoutEch)
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
        resolver.close()
    }

    private fun startSession(network: DefaultNetwork?) {
        val usableNetwork = network?.takeIf { it.blocked != true }
        sessions.endCurrent()
        tcpProbe.abort()
        bodyProbe.abort()
        sessions.switchTo(usableNetwork)
        if (usableNetwork != null) {
            tcpProber.probeStaleHosts()
            bodyProber.probeStaleHosts()
            resolver.preresolve()
            persistence.saveLater()
        }
    }

    private companion object {
        const val MAX_NETWORKS = 8
        const val PROBE_TIMEOUT_MILLIS = 5_000
        const val BODY_PROBE_DEADLINE_MILLIS = 15_000L
        val BODY_PROBE_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1)
        const val RESOLVE_WAIT_MILLIS = 2_000L
    }
}
