package net.qrator.failoverbeacon.failover.fakes

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import net.qrator.failoverbeacon.failover.model.LayerResult

internal class FakeTcpProbe {
    private val results = ConcurrentHashMap<InetAddress, () -> LayerResult>()
    val runs = CopyOnWriteArrayList<Pair<InetAddress, Int>>()

    fun answer(address: InetAddress, result: LayerResult) {
        results[address] = { result }
    }

    fun fail(address: InetAddress, failure: RuntimeException) {
        results[address] = { throw failure }
    }

    fun run(address: InetAddress, port: Int): LayerResult {
        runs += address to port
        return results[address]?.invoke() ?: LayerResult.Failed
    }
}
