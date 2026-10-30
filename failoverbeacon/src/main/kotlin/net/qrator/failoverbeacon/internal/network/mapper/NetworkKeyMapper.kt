package net.qrator.failoverbeacon.internal.network.mapper

import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.Inet4Address
import net.qrator.failoverbeacon.core.network.model.NetworkCarrier

internal class NetworkKeyMapper {
    private val transportMapper = NetworkTransportMapper()

    fun toKey(
        capabilities: NetworkCapabilities,
        linkProperties: LinkProperties,
        carrier: NetworkCarrier?
    ): String {
        val transport = transportMapper.toTransport(capabilities)
        val vpn = if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "+VPN" else ""
        val operator = carrier?.mcc?.let { mcc -> carrier.mnc?.let { mnc -> "$mcc-$mnc" } }
        return "$transport$vpn/${operator ?: addressPrefix(linkProperties)}"
    }

    private fun addressPrefix(linkProperties: LinkProperties): String {
        val addresses = linkProperties.linkAddresses
            .map { it.address }
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
        val ipv4 = addresses.firstOrNull { it is Inet4Address }
        if (ipv4 != null) {
            return ipv4.address.take(3).joinToString(".") { (it.toInt() and 0xFF).toString() }
        }
        return addresses.firstOrNull()?.address?.take(8)
            ?.joinToString("") { "%02x".format(it) }
            .orEmpty()
    }
}
