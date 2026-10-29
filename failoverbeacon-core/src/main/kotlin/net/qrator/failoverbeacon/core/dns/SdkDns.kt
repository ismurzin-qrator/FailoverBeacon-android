package net.qrator.failoverbeacon.core.dns

import java.net.InetAddress

interface SdkDns {
    fun manages(host: String): Boolean

    fun preparedAddresses(host: String): List<InetAddress>?

    fun onResolved(host: String, addresses: List<InetAddress>): List<InetAddress>

    fun onResolveFailed(host: String): List<InetAddress>?
}
