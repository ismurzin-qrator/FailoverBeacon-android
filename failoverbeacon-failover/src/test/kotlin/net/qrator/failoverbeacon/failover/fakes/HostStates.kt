package net.qrator.failoverbeacon.failover.fakes

import java.net.InetAddress
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig
import net.qrator.failoverbeacon.failover.model.HostState
import net.qrator.failoverbeacon.failover.model.LayerResult

internal val HostState.ips: List<InetAddress> get() = addresses.map { it.address }

internal val HostState.tcpResults: Map<InetAddress, LayerResult>
    get() = addresses.associate { it.address to it.tcp }

internal fun hostConfig(
    host: String,
    additional: List<InetAddress> = emptyList(),
    port: Int = 443,
    probeConnections: Boolean = true
) = FailoverHostConfig(host, port, additional, probeConnections)
