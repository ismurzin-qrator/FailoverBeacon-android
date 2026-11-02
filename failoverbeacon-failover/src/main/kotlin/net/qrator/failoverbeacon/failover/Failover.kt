package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import net.qrator.failoverbeacon.core.SdkFeature
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.call.ExchangeSubscriber
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.network.model.DefaultNetwork
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
) : SdkFeature,
    ExchangeSubscriber {
    private val repository = AddressRepository(
        hosts = hosts,
        clock = sdk.elapsedRealtimeClock,
        refreshIntervalNanos = settings.refreshIntervalNanos,
        maxNetworks = settings.maxNetworks
    )
    private val tcpProbe = TcpProbe(sdk.clock, settings.probeTimeoutMillis)
    private val prober = AddressProber(
        hosts = hosts,
        repository = repository,
        connect = tcpProbe::connect,
        executor = sdk.threads.failoverProbe,
        probeOnNetworkChange = settings.probeOnNetworkChange
    )
    private val refresher = AddressRefresher(
        resolve = resolve,
        repository = repository,
        executor = sdk.threads.failoverResolve,
        hosts = hosts.map { it.host },
        preresolveOnNetworkChange = settings.preresolveOnNetworkChange,
        waitMillis = settings.resolveWaitMillis
    )
    private val dns = FailoverDns(repository, refresher, onLookup = ::refreshDue)

    fun subscribe() {
        repository.addAddressListener(prober::probe)
        sdk.dns = dns
        sdk.exchangeBus.subscribe(this)
        sdk.addFeature(this)
    }

    override fun onNetworkChanged(network: DefaultNetwork?) {
        val usableNetwork = network?.takeIf { it.blocked != true }
        repository.switchTo(usableNetwork)
        if (usableNetwork != null) {
            prober.probeAll()
            refresher.preresolve()
        }
    }

    override fun onExchange(exchange: Exchange) = refreshDue()

    override fun close() {
        tcpProbe.close()
        refresher.close()
    }

    private fun refreshDue() {
        prober.probeAll()
        refresher.refreshDue()
    }
}
