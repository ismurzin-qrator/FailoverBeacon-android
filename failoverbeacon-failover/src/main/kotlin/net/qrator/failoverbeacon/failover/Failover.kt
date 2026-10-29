package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import net.qrator.failoverbeacon.core.SdkFeature
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.thread.SdkExecutor
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.HostRecord
import net.qrator.failoverbeacon.failover.ranking.AddressRanking
import net.qrator.failoverbeacon.failover.ranking.TcpProbe
import net.qrator.failoverbeacon.failover.resolve.AddressRefresher
import net.qrator.failoverbeacon.failover.store.AddressRecorder
import net.qrator.failoverbeacon.failover.store.AddressStore

class Failover(
    private val sdk: SdkInstance,
    hosts: List<FailoverHostConfig>,
    settings: FailoverSettings
) : SdkFeature {
    private val resolveExecutor = SdkExecutor(
        name = "failover",
        coreThreads = 0,
        maxThreads = settings.resolveThreads,
        keepAliveMillis = settings.threadKeepAliveMillis,
        queue = SynchronousQueue()
    )
    private val probeExecutor = SdkExecutor(
        name = "failover-probe",
        coreThreads = settings.probeThreads,
        maxThreads = settings.probeThreads,
        keepAliveMillis = settings.threadKeepAliveMillis,
        queue = LinkedBlockingQueue()
    ).apply { allowCoreThreadTimeOut(true) }
    private val store = AddressStore(hosts.map { HostRecord(it.host, it.additionalAddresses) })
    private val recorder = AddressRecorder(store, sdk.clock)
    private val tcpProbe = TcpProbe(sdk.clock, settings.probeTimeoutMillis)
    private val ranking = AddressRanking(
        hosts = hosts,
        store = store,
        recorder = recorder,
        connect = tcpProbe::connect,
        executor = probeExecutor
    )
    private val refresher = AddressRefresher(
        resolve = { InetAddress.getAllByName(it).toList() },
        recorder = recorder,
        ranking = ranking,
        executor = resolveExecutor
    )
    private val dns = FailoverDns(
        store = store,
        refresher = refresher,
        clock = sdk.clock,
        maxAgeNanos = settings.maxAddressAgeNanos
    )
    private val hostNames = hosts.map { it.host }

    fun subscribe() {
        sdk.dns = dns
        sdk.addFeature(this)
    }

    fun start() {
        hostNames.forEach(ranking::probe)
        refresher.refreshAll(hostNames)
    }

    override fun close() {
        tcpProbe.close()
        resolveExecutor.shutdownNow()
        probeExecutor.shutdownNow()
    }
}
