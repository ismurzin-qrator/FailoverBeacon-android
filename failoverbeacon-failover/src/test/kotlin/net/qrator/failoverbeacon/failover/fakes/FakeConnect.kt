package net.qrator.failoverbeacon.failover.fakes

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import net.qrator.failoverbeacon.failover.model.ProbeResult

internal class FakeConnect {
    private val results = ConcurrentHashMap<InetAddress, () -> ProbeResult>()
    val connects = CopyOnWriteArrayList<Pair<InetAddress, Int>>()

    fun answer(address: InetAddress, result: ProbeResult) {
        results[address] = { result }
    }

    fun fail(address: InetAddress, failure: RuntimeException) {
        results[address] = { throw failure }
    }

    fun connect(address: InetAddress, port: Int): ProbeResult {
        connects += address to port
        return results[address]?.invoke() ?: ProbeResult.Failed
    }
}
