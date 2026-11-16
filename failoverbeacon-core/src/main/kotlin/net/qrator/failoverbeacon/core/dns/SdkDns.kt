package net.qrator.failoverbeacon.core.dns

import java.net.InetAddress

interface SdkDns {
    val isFallbackWithoutEchAllowed: Boolean

    fun manages(host: String): Boolean

    fun lookup(host: String): List<InetAddress>?

    fun answersWithoutDns(host: String): Boolean
}
