package net.qrator.failoverbeacon.failover.store

import java.net.InetAddress
import net.qrator.failoverbeacon.core.time.MonotonicClock
import net.qrator.failoverbeacon.failover.model.ProbeResult

internal class AddressRecorder(private val store: AddressStore, private val clock: MonotonicClock) {
    fun recordResolved(host: String, addresses: List<InetAddress>) {
        store.update(host) { it.withResolved(addresses, clock.nanoTime()) }
    }

    fun recordProbe(host: String, address: InetAddress, result: ProbeResult) {
        store.update(host) { it.withProbe(address, result) }
    }
}
