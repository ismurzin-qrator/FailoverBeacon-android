package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import java.util.concurrent.SynchronousQueue
import net.qrator.failoverbeacon.core.SdkFeature
import net.qrator.failoverbeacon.core.SdkInstance
import net.qrator.failoverbeacon.core.thread.SdkExecutor
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.HostRecord
import net.qrator.failoverbeacon.failover.resolve.AddressRefresher
import net.qrator.failoverbeacon.failover.resolve.DnsRecorder
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
    private val store = AddressStore(hosts.map { HostRecord(it.host, it.additionalAddresses) })
    private val recorder = DnsRecorder(store, sdk.clock)
    private val refresher = AddressRefresher(
        resolve = { InetAddress.getAllByName(it).toList() },
        recorder = recorder,
        executor = resolveExecutor
    )
    private val dns = FailoverDns(
        store = store,
        recorder = recorder,
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
        refresher.refreshAll(hostNames)
    }

    override fun close() {
        resolveExecutor.shutdownNow()
    }
}
