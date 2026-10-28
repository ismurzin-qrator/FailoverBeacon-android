package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

class FailoverHostConfig(val host: String, val additionalAddresses: List<InetAddress>)
