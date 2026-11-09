package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import net.qrator.failoverbeacon.core.SdkFeature
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.ExchangeSubscriber
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.network.model.DefaultNetwork
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.MaxAge
import net.qrator.failoverbeacon.failover.network.KnownNetworks
import net.qrator.failoverbeacon.failover.network.NetworkSessions
import net.qrator.failoverbeacon.failover.probe.BodyProbe
import net.qrator.failoverbeacon.failover.probe.BodyProber
import net.qrator.failoverbeacon.failover.probe.TcpProbe
import net.qrator.failoverbeacon.failover.probe.TcpProber
import net.qrator.failoverbeacon.failover.resolve.AddressResolver
import net.qrator.failoverbeacon.failover.storage.KnownNetworksPersistence
import net.qrator.failoverbeacon.failover.traffic.TrafficFeedback

class Failover(
    private val sdk: SdkInstance,
    hostConfigs: List<FailoverHostConfig>,
    queryDns: (String) -> List<InetAddress>,
    settings: FailoverSettings
) : SdkFeature,
    ExchangeSubscriber {
    private val maxAge = MaxAge(sdk.epochClock, settings.refreshIntervalMillis)
    private val fallbackWithoutEch = settings.fallbackWithoutEch
    private val bodyProbeMaxAge = MaxAge(sdk.epochClock, BODY_PROBE_INTERVAL_MILLIS)
    private val networks = KnownNetworks(hostConfigs, MAX_NETWORKS)
    private val sessions = NetworkSessions(networks, maxAge)
    private val persistence = KnownNetworksPersistence(
        cacheDir = sdk.appContext::getCacheDir,
        networks = networks,
        hostConfigs = hostConfigs,
        clock = sdk.epochClock,
        bodyProbeMaxAge = bodyProbeMaxAge,
        storage = sdk.threads.serial,
        scheduler = sdk.threads.scheduler
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
        maxAge = bodyProbeMaxAge,
        executor = sdk.threads.probes
    )
    private val tcpProber = TcpProber(
        sessions = sessions,
        connect = tcpProbe::run,
        executor = sdk.threads.probes,
        bodyProber = bodyProber
    )
    private val resolver = AddressResolver(
        sessions = sessions,
        queryDns = queryDns,
        executor = sdk.threads.dns,
        tcpProber = tcpProber,
        maxAge = maxAge,
        waitMillis = RESOLVE_WAIT_MILLIS
    )

    fun subscribe() {
        sdk.dns = FailoverDns(sessions, tcpProber, resolver, maxAge, fallbackWithoutEch) {
            SdkRegistry.isFailoverEnabled
        }
        sdk.exchangeBus.subscribe(this)
        sdk.addFeature(this)
    }

    fun start() = persistence.restore()

    override fun onNetworkChanged(network: DefaultNetwork?) =
        sdk.threads.serial.execute { startSession(network) }

    override fun onExchange(exchange: Exchange) {
        if (!SdkRegistry.isFailoverEnabled) return
        feedback.save(exchange)
        tcpProber.probeStaleHosts()
        bodyProber.probeStaleHosts()
        resolver.resolveStaleHosts()
        persistence.saveLater()
    }

    override fun clearData() {
        persistence.clear()
        sdk.threads.serial.execute { sessions.restart() }
    }

    override fun close() {
        sessions.endCurrent()
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
        if (usableNetwork != null && SdkRegistry.isFailoverEnabled) {
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
