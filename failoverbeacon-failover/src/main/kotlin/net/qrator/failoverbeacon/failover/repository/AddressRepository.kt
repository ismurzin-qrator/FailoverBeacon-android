package net.qrator.failoverbeacon.failover.repository

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import net.qrator.failoverbeacon.core.network.model.DefaultNetwork
import net.qrator.failoverbeacon.core.time.MonotonicClock
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.HostRecord
import net.qrator.failoverbeacon.failover.model.ProbeResult

internal class AddressRepository(
    private val hosts: List<FailoverHostConfig>,
    private val clock: MonotonicClock,
    private val refreshIntervalNanos: Long,
    private val maxNetworks: Int
) {
    private val networks = LinkedHashMap<String, ConcurrentHashMap<String, HostRecord>>()
    private val addressListeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile
    private var current: Session? = null

    @Volatile
    private var firstHandle: Long? = null

    val currentHandle: Long? get() = current?.handle

    val isOnFirstNetwork: Boolean get() = firstHandle != null && firstHandle == currentHandle

    operator fun get(host: String): HostRecord? = current?.records?.get(host)

    fun isFresh(host: String): Boolean =
        get(host)?.isFresh(clock.nanoTime(), refreshIntervalNanos) == true

    fun isAnswerNeeded(record: HostRecord): Boolean =
        !record.hasResolveFailed && !record.isFresh(clock.nanoTime(), refreshIntervalNanos)

    fun switchTo(network: DefaultNetwork?) {
        if (firstHandle == null) firstHandle = network?.handle
        current = network?.let { Session(it.handle, recordsFor(it.key)) }
    }

    fun saveResolved(handle: Long, host: String, addresses: List<InetAddress>) {
        val records = recordsOf(handle) ?: return
        var hasChanged = false
        records.computeIfPresent(host) { _, oldRecord ->
            val newRecord = oldRecord.withResolved(addresses, clock.nanoTime())
            hasChanged = newRecord.addresses != oldRecord.addresses
            newRecord
        }
        if (hasChanged) {
            addressListeners.forEach { listener -> listener(host) }
        }
    }

    fun saveResolveFailure(handle: Long, host: String) {
        recordsOf(handle)?.computeIfPresent(host) { _, record -> record.withResolveFailure() }
    }

    fun saveProbe(handle: Long, host: String, address: InetAddress, result: ProbeResult) {
        val records = recordsOf(handle) ?: return
        records.computeIfPresent(host) { _, record -> record.withProbe(address, result) }
    }

    fun startResolveRound(handle: Long, host: String): Boolean = startRound(
        handle,
        host,
        HostRecord::isResolveRoundDue,
        HostRecord::withResolveRound
    )

    fun startProbeRound(handle: Long, host: String): Boolean = startRound(
        handle,
        host,
        HostRecord::isProbeRoundDue,
        HostRecord::withProbeRound
    )

    fun addAddressListener(listener: (host: String) -> Unit) {
        addressListeners += listener
    }

    private fun startRound(
        handle: Long,
        host: String,
        isDue: HostRecord.(Long, Long) -> Boolean,
        withRound: HostRecord.(Long) -> HostRecord
    ): Boolean {
        val records = recordsOf(handle) ?: return false
        var isStarted = false
        records.computeIfPresent(host) { _, record ->
            val now = clock.nanoTime()
            isStarted = record.isDue(now, refreshIntervalNanos)
            if (isStarted) record.withRound(now) else record
        }
        return isStarted
    }

    private fun recordsOf(handle: Long) = current?.takeIf { it.handle == handle }?.records

    private fun recordsFor(key: String) = synchronized(networks) {
        val records = networks.remove(key) ?: newRecords()
        networks[key] = records
        if (networks.size > maxNetworks) networks.remove(networks.keys.first())
        records
    }

    private fun newRecords() =
        ConcurrentHashMap(hosts.associate { it.host to HostRecord(it.additionalAddresses) })

    private class Session(val handle: Long, val records: ConcurrentHashMap<String, HostRecord>)
}
