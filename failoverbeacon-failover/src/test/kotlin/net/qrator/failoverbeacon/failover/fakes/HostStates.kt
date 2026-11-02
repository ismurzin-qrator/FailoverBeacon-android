package net.qrator.failoverbeacon.failover.fakes

import java.net.InetAddress
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.HostState
import net.qrator.failoverbeacon.failover.model.ProbeResult

internal val HostState.ips: List<InetAddress> get() = addresses.map { it.address }

internal val HostState.probes: Map<InetAddress, ProbeResult>
    get() = addresses.associate { it.address to it.probe }

internal fun hostConfig(
    host: String,
    additional: List<InetAddress> = emptyList(),
    port: Int = 443,
    probeConnections: Boolean = true
) = FailoverHostConfig(host, port, additional, probeConnections)
