package net.qrator.failoverbeacon.failover.probe

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.ProbeResult
import net.qrator.failoverbeacon.failover.repository.AddressRepository

internal class AddressProber(
    hosts: List<FailoverHostConfig>,
    private val repository: AddressRepository,
    private val connect: (InetAddress, Int) -> ProbeResult,
    private val executor: Executor,
    private val probeOnNetworkChange: Boolean
) {
    private val ports = hosts.filter { it.probeConnections }.associate { it.host to it.port }
    private val probingAddresses = ConcurrentHashMap.newKeySet<Probe>()

    fun probe(host: String) {
        val port = ports[host] ?: return
        if (!probeOnNetworkChange && !repository.isOnFirstNetwork) return
        val handle = repository.currentHandle ?: return
        val addresses = repository[host]?.addresses ?: return
        val ipv4Addresses = addresses.keys.filterIsInstance<Inet4Address>()
        if (ipv4Addresses.size < 2) return
        ipv4Addresses
            .filter { addresses[it] == ProbeResult.Unknown }
            .forEach { address ->
                if (probingAddresses.add(Probe(host, address, handle))) {
                    executor.execute { probeAddress(host, port, address, handle) }
                }
            }
    }

    private fun probeAddress(host: String, port: Int, address: InetAddress, handle: Long) {
        try {
            val result = connect(address, port)
            SdkRegistry.logger.debug {
                "[Failover] $host ${address.hostAddress}: ${describe(result)}"
            }
            repository.saveProbe(handle, host, address, result)
        } finally {
            probingAddresses -= Probe(host, address, handle)
        }
    }

    private fun describe(result: ProbeResult): String = when (result) {
        is ProbeResult.Connected -> "${TimeUnit.NANOSECONDS.toMillis(result.connectNanos)} ms"
        ProbeResult.Failed -> "failed"
        ProbeResult.Unknown -> "unknown"
    }

    private data class Probe(val host: String, val address: InetAddress, val handle: Long)
}
