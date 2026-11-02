package net.qrator.failoverbeacon.failover.fakes

import net.qrator.failoverbeacon.core.network.model.DefaultNetwork

fun network(handle: Long, key: String = "WIFI/192.0.2") =
    DefaultNetwork(handle, key, blocked = false)
