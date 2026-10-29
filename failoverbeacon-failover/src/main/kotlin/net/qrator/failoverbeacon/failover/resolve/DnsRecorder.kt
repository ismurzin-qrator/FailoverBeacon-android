package net.qrator.failoverbeacon.failover.resolve

import java.net.InetAddress
import net.qrator.failoverbeacon.core.time.MonotonicClock
import net.qrator.failoverbeacon.failover.store.AddressStore

internal class DnsRecorder(private val store: AddressStore, private val clock: MonotonicClock) {
    fun record(host: String, addresses: List<InetAddress>) {
        store.update(host) { it.withResolved(addresses, clock.nanoTime()) }
    }
}
