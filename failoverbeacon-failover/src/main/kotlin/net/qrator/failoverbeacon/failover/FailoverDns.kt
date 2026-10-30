package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import net.qrator.failoverbeacon.core.dns.SdkDns
import net.qrator.failoverbeacon.core.time.MonotonicClock
import net.qrator.failoverbeacon.failover.repository.AddressRepository
import net.qrator.failoverbeacon.failover.resolve.AddressRefresher

internal class FailoverDns(
    private val repository: AddressRepository,
    private val refresher: AddressRefresher,
    private val clock: MonotonicClock,
    private val maxAgeNanos: Long
) : SdkDns {
    override fun manages(host: String): Boolean = repository[host] != null

    override fun lookup(host: String): List<InetAddress>? {
        val record = repository[host] ?: return null
        if (record.isFresh(clock.nanoTime(), maxAgeNanos)) {
            refresher.refresh(host)
        } else {
            refresher.refreshNow(host)
        }
        return repository[host]?.addresses?.keys?.toList()?.ifEmpty { null }
    }
}
