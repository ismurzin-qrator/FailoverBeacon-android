package net.qrator.failoverbeacon.failover.store

import java.util.concurrent.ConcurrentHashMap
import net.qrator.failoverbeacon.failover.model.HostRecord

internal class AddressStore(records: List<HostRecord>) {
    private val records = ConcurrentHashMap(records.associateBy { it.host })

    operator fun get(host: String): HostRecord? = records[host]

    fun update(host: String, change: (HostRecord) -> HostRecord) {
        records.computeIfPresent(host) { _, record -> change(record) }
    }
}
