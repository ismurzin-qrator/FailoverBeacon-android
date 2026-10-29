package net.qrator.failoverbeacon.failover.ranking

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.ProbeResult
import net.qrator.failoverbeacon.failover.store.AddressRecorder
import net.qrator.failoverbeacon.failover.store.AddressStore

internal class AddressRanking(
    hosts: List<FailoverHostConfig>,
    private val store: AddressStore,
    private val recorder: AddressRecorder,
    private val connect: (InetAddress, Int) -> ProbeResult,
    private val executor: Executor
) {
    private val ports = hosts.filter { it.probeConnections }.associate { it.host to it.port }
    private val probingAddresses = ConcurrentHashMap.newKeySet<Pair<String, InetAddress>>()

    fun probe(host: String) {
        val port = ports[host] ?: return
        val addresses = store[host]?.addresses ?: return
        val ipv4Addresses = addresses.keys.filterIsInstance<Inet4Address>()
        if (ipv4Addresses.size < 2) return
        ipv4Addresses
            .filter { addresses[it] == ProbeResult.Unknown }
            .forEach { address ->
                if (probingAddresses.add(host to address)) {
                    executor.execute { probeAddress(host, port, address) }
                }
            }
    }

    private fun probeAddress(host: String, port: Int, address: InetAddress) {
        try {
            val result = connect(address, port)
            SdkRegistry.logger.debug {
                "[Failover] $host ${address.hostAddress}: ${describe(result)}"
            }
            recorder.recordProbe(host, address, result)
        } finally {
            probingAddresses -= host to address
        }
    }

    private fun describe(result: ProbeResult): String = when (result) {
        is ProbeResult.Connected -> "${TimeUnit.NANOSECONDS.toMillis(result.connectNanos)} ms"
        ProbeResult.Failed -> "failed"
        ProbeResult.Unknown -> "unknown"
    }
}
