package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import net.qrator.failoverbeacon.core.dns.SdkDns
import net.qrator.failoverbeacon.core.time.MonotonicClock
import net.qrator.failoverbeacon.failover.resolve.AddressRefresher
import net.qrator.failoverbeacon.failover.resolve.DnsRecorder
import net.qrator.failoverbeacon.failover.store.AddressStore

internal class FailoverDns(
    private val store: AddressStore,
    private val recorder: DnsRecorder,
    private val refresher: AddressRefresher,
    private val clock: MonotonicClock,
    private val maxAgeNanos: Long
) : SdkDns {
    override fun manages(host: String): Boolean = store[host] != null

    override fun preparedAddresses(host: String): List<InetAddress>? {
        val record = store[host] ?: return null
        if (!record.isFresh(clock.nanoTime(), maxAgeNanos)) return null
        refresher.refresh(host)
        return record.addresses
    }

    override fun onResolved(host: String, addresses: List<InetAddress>): List<InetAddress> {
        val record = store[host] ?: return addresses
        recorder.record(host, addresses)
        return (addresses + record.additionalAddresses).distinct()
    }

    override fun onResolveFailed(host: String): List<InetAddress>? =
        store[host]?.addresses?.ifEmpty { null }
}
