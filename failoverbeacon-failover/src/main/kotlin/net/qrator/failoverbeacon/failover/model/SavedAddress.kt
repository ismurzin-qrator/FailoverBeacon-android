package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

internal data class SavedAddress(
    val address: InetAddress,
    val isFromDns: Boolean,
    val tcpNanos: Long?,
    val bodyProbe: SavedBodyProbe?
)
