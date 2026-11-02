package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

internal data class HostAddress(val address: InetAddress, val probe: ProbeResult)
