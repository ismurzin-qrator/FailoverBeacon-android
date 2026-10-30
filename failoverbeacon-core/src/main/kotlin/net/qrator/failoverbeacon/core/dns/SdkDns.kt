package net.qrator.failoverbeacon.core.dns

import java.net.InetAddress

interface SdkDns {
    fun manages(host: String): Boolean

    fun lookup(host: String): List<InetAddress>?
}
