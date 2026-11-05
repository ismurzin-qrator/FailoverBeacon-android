package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

class FailoverHostConfig(
    val host: String,
    val port: Int,
    val additionalAddresses: List<InetAddress>,
    val tcpProbe: Boolean,
    val bodyProbe: BodyProbeConfig? = null
)
