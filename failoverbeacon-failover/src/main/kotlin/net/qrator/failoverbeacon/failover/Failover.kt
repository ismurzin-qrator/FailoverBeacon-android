package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import net.qrator.failoverbeacon.core.SdkFeature
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.probe.AddressProber
import net.qrator.failoverbeacon.failover.probe.TcpProbe
import net.qrator.failoverbeacon.failover.repository.AddressRepository
import net.qrator.failoverbeacon.failover.resolve.AddressRefresher

class Failover(
    private val sdk: SdkInstance,
    hosts: List<FailoverHostConfig>,
    resolve: (String) -> List<InetAddress>,
    settings: FailoverSettings
) : SdkFeature {
    private val repository = AddressRepository(hosts, sdk.elapsedRealtimeClock)
    private val tcpProbe = TcpProbe(sdk.clock, settings.probeTimeoutMillis)
    private val prober = AddressProber(
        hosts = hosts,
        repository = repository,
        connect = tcpProbe::connect,
        executor = sdk.threads.failoverProbe
    )
    private val refresher = AddressRefresher(
        resolve = resolve,
        repository = repository,
        executor = sdk.threads.failoverResolve
    )
    private val dns = FailoverDns(
        repository = repository,
        refresher = refresher,
        clock = sdk.elapsedRealtimeClock,
        maxAgeNanos = settings.maxAddressAgeNanos
    )
    private val hostNames = hosts.map { it.host }

    fun subscribe() {
        repository.addAddressListener(prober::probe)
        sdk.dns = dns
        sdk.addFeature(this)
    }

    fun start() {
        hostNames.forEach(prober::probe)
        refresher.refreshAll(hostNames)
    }

    override fun close() {
        tcpProbe.close()
    }
}
