package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import net.qrator.failoverbeacon.core.dns.SdkDns
import net.qrator.failoverbeacon.failover.repository.AddressRepository
import net.qrator.failoverbeacon.failover.resolve.AddressRefresher

internal class FailoverDns(
    private val repository: AddressRepository,
    private val refresher: AddressRefresher,
    private val onLookup: () -> Unit
) : SdkDns {
    override fun manages(host: String): Boolean = repository[host] != null

    override fun lookup(host: String): List<InetAddress>? {
        val record = repository[host] ?: return null
        onLookup()
        val current = if (repository.isAnswerNeeded(record)) {
            refresher.refreshAndWait(host)
            repository[host]
        } else {
            refresher.refresh(host)
            record
        }
        return current?.addresses?.keys?.toList()?.ifEmpty { null }
    }
}
