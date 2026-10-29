package net.qrator.failoverbeacon.failover.repository

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import net.qrator.failoverbeacon.core.time.MonotonicClock
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.HostRecord
import net.qrator.failoverbeacon.failover.model.ProbeResult

internal class AddressRepository(
    hosts: List<FailoverHostConfig>,
    private val clock: MonotonicClock
) {
    private val records = ConcurrentHashMap(
        hosts.associate { it.host to HostRecord(it.host, it.additionalAddresses) }
    )
    private val addressListeners = CopyOnWriteArrayList<(String) -> Unit>()

    operator fun get(host: String): HostRecord? = records[host]

    fun saveResolved(host: String, addresses: List<InetAddress>) {
        var hasNewAddresses = false
        records.computeIfPresent(host) { _, oldRecord ->
            val newRecord = oldRecord.withResolved(addresses, clock.nanoTime())
            hasNewAddresses = newRecord.addresses.keys != oldRecord.addresses.keys
            newRecord
        }
        if (hasNewAddresses) {
            addressListeners.forEach { listener -> listener(host) }
        }
    }

    fun saveProbe(host: String, address: InetAddress, result: ProbeResult) {
        records.computeIfPresent(host) { _, record -> record.withProbe(address, result) }
    }

    fun addAddressListener(listener: (host: String) -> Unit) {
        addressListeners += listener
    }
}
