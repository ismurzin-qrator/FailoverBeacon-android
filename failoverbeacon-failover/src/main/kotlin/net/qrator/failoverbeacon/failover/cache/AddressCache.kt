package net.qrator.failoverbeacon.failover.cache

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import net.qrator.failoverbeacon.core.time.MonotonicClock

internal class AddressCache(private val clock: MonotonicClock, private val maxAgeNanos: Long) {
    private class Entry(val addresses: List<InetAddress>, val savedAtNanos: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun put(host: String, addresses: List<InetAddress>) {
        entries[host] = Entry(addresses, clock.nanoTime())
    }

    fun freshAddresses(host: String): List<InetAddress>? {
        val entry = entries[host] ?: return null
        return if (clock.nanoTime() - entry.savedAtNanos <= maxAgeNanos) entry.addresses else null
    }

    fun lastAddresses(host: String): List<InetAddress>? = entries[host]?.addresses
}
