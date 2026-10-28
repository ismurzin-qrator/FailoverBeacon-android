package net.qrator.failoverbeacon.failover

import java.net.InetAddress
import net.qrator.failoverbeacon.core.dns.SdkDns
import net.qrator.failoverbeacon.failover.cache.AddressCache
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.resolve.AddressRefresher

internal class FailoverDns(
    hosts: List<FailoverHostConfig>,
    private val cache: AddressCache,
    private val refresher: AddressRefresher
) : SdkDns {
    private val hostsByName = hosts.associateBy { it.host }

    override fun preparedAddresses(host: String): List<InetAddress>? {
        val config = hostsByName[host] ?: return null
        val prepared = cache.freshAddresses(host) ?: return null
        refresher.refresh(host)
        return (prepared + config.additionalAddresses).distinct()
    }

    override fun onResolved(host: String, addresses: List<InetAddress>): List<InetAddress> {
        val config = hostsByName[host] ?: return addresses
        if (addresses.isNotEmpty()) cache.put(host, addresses)
        return (addresses + config.additionalAddresses).distinct()
    }

    override fun onResolveFailed(host: String): List<InetAddress>? {
        val config = hostsByName[host] ?: return null
        val last = cache.lastAddresses(host).orEmpty()
        return (last + config.additionalAddresses).distinct().ifEmpty { null }
    }
}
