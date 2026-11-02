package net.qrator.failoverbeacon.failover.resolve

import java.io.Closeable
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.core.logging.info
import net.qrator.failoverbeacon.failover.repository.AddressRepository

internal class AddressRefresher(
    private val resolve: (String) -> List<InetAddress>,
    private val repository: AddressRepository,
    private val executor: Executor,
    private val hosts: List<String>,
    private val preresolveOnNetworkChange: Boolean,
    private val waitMillis: Long
) : Closeable {
    private val refreshing = ConcurrentHashMap<Pair<String, Long>, CountDownLatch>()

    @Volatile
    private var isClosed = false

    fun preresolve() {
        if (preresolveOnNetworkChange || repository.isOnFirstNetwork) {
            refreshAll(hosts.filterNot(repository::isFresh))
        }
    }

    fun refreshDue() {
        val handle = repository.currentHandle ?: return
        hosts
            .filter { canRefresh(it) && repository.startResolveRound(handle, it) }
            .forEach(::refresh)
    }

    fun refreshAll(hosts: List<String>) {
        val handle = repository.currentHandle ?: return
        val pending = AtomicInteger(hosts.size)
        val resolved = AtomicInteger()
        for (host in hosts) {
            submit(host, handle) { success ->
                if (success) resolved.incrementAndGet()
                if (pending.decrementAndGet() == 0) {
                    SdkRegistry.logger.info {
                        "[Failover] Resolved ${resolved.get()} of ${hosts.size} hosts"
                    }
                }
            }
        }
    }

    fun refresh(host: String) {
        val handle = repository.currentHandle ?: return
        submit(host, handle) {}
    }

    fun refreshAndWait(host: String) {
        val handle = repository.currentHandle ?: return
        if (isClosed) return
        val done = submit(host, handle) {}
        try {
            if (!done.await(waitMillis, TimeUnit.MILLISECONDS)) {
                repository.saveResolveFailure(handle, host)
                SdkRegistry.logger.debug { "[Failover] $host: DNS wait timed out" }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        isClosed = true
        refreshing.values.forEach(CountDownLatch::countDown)
    }

    private fun canRefresh(host: String): Boolean =
        preresolveOnNetworkChange || repository.isOnFirstNetwork ||
            repository[host]?.hasAnswer == true

    private fun submit(host: String, handle: Long, onDone: (Boolean) -> Unit): CountDownLatch {
        val done = CountDownLatch(1)
        refreshing.putIfAbsent(host to handle, done)?.let { return it }
        executor.execute { resolveInBackground(host, handle, done, onDone) }
        return done
    }

    private fun resolveInBackground(
        host: String,
        handle: Long,
        done: CountDownLatch,
        onDone: (Boolean) -> Unit
    ) {
        var success = false
        try {
            if (repository.currentHandle == handle) success = resolveAndSave(host, handle)
        } finally {
            if (!success) repository.saveResolveFailure(handle, host)
            refreshing.remove(host to handle)
            done.countDown()
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
