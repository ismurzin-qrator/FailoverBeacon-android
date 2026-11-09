package net.qrator.failoverbeacon.failover.fakes

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal class FakeTcpProbe {
    private val results = ConcurrentHashMap<InetAddress, () -> Long?>()
    val runs = CopyOnWriteArrayList<Pair<InetAddress, Int>>()

    fun answer(address: InetAddress, nanos: Long) {
        results[address] = { nanos }
    }

    fun fail(address: InetAddress, failure: RuntimeException) {
        results[address] = { throw failure }
    }

    fun run(address: InetAddress, port: Int): Long? {
        runs += address to port
        return results[address]?.invoke()
    }
}
