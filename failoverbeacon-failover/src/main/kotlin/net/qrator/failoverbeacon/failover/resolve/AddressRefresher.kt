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
    private val refreshing = ConcurrentHashMap.newKeySet<Pair<String, Long>>()

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

    fun refreshNow(host: String) {
        val handle = repository.currentHandle ?: return
        resolveAndSave(host, handle)
    }

    private fun submit(host: String, onDone: (Boolean) -> Unit) {
        val handle = repository.currentHandle ?: return
        if (!refreshing.add(host to handle)) return
        executor.execute { resolveInBackground(host, handle, onDone) }
    }

    private fun resolveInBackground(host: String, handle: Long, onDone: (Boolean) -> Unit) {
        var success = false
        try {
            success = resolveAndSave(host, handle)
        } finally {
            refreshing.remove(host to handle)
            onDone(success)
        }
    }

    private fun resolveAndSave(host: String, handle: Long): Boolean = try {
        val addresses = resolve(host)
        repository.saveResolved(handle, host, addresses)
        SdkRegistry.logger.debug { "[Failover] $host: ${addresses.size} addresses" }
        addresses.isNotEmpty()
    } catch (e: UnknownHostException) {
        SdkRegistry.logger.debug(e) { "[Failover] $host: DNS failed" }
        false
    }
}
