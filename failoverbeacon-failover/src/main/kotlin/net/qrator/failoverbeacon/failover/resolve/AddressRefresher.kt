package net.qrator.failoverbeacon.failover.resolve

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.core.logging.info
import net.qrator.failoverbeacon.failover.repository.AddressRepository

internal class AddressRefresher(
    private val resolve: (String) -> List<InetAddress>,
    private val repository: AddressRepository,
    private val executor: Executor
) {
    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    fun refreshAll(hosts: List<String>) {
        val pending = AtomicInteger(hosts.size)
        val resolved = AtomicInteger()
        for (host in hosts) {
            submit(host) { success ->
                if (success) resolved.incrementAndGet()
                if (pending.decrementAndGet() == 0) {
                    SdkRegistry.logger.info {
                        "[Failover] Resolved ${resolved.get()} of ${hosts.size} hosts"
                    }
                }
            }
        }
    }

    fun refresh(host: String) = submit(host) {}

    private fun submit(host: String, onDone: (Boolean) -> Unit) {
        if (!refreshing.add(host)) return
        executor.execute { resolveInBackground(host, onDone) }
    }

    private fun resolveInBackground(host: String, onDone: (Boolean) -> Unit) {
        var success = false
        try {
            val addresses = resolve(host)
            repository.saveResolved(host, addresses)
            success = addresses.isNotEmpty()
            SdkRegistry.logger.debug { "[Failover] $host: ${addresses.size} addresses" }
        } catch (e: UnknownHostException) {
            SdkRegistry.logger.debug(e) { "[Failover] $host: DNS failed" }
        } finally {
            refreshing.remove(host)
            onDone(success)
        }
    }
}
