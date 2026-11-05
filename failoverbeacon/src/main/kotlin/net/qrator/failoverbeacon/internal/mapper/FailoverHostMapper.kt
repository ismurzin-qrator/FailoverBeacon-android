package net.qrator.failoverbeacon.internal.mapper

import net.qrator.failoverbeacon.FailoverHost
import net.qrator.failoverbeacon.failover.model.BodyProbeConfig
import net.qrator.failoverbeacon.failover.model.FailoverHostConfig

internal class FailoverHostMapper {
    fun toConfig(host: FailoverHost): FailoverHostConfig = FailoverHostConfig(
        host.host,
        host.port,
        host.additionalAddresses,
        host.tcpProbe,
        host.bodyProbe?.let { BodyProbeConfig(it.path, it.maxBytes) }
    )
}
