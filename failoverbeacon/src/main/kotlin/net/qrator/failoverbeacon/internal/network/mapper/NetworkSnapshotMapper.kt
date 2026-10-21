package net.qrator.failoverbeacon.internal.network.mapper

import android.net.NetworkCapabilities
import android.os.Build
import net.qrator.failoverbeacon.core.network.model.NetworkCarrier
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot

internal class NetworkSnapshotMapper {
    private val transportMapper = NetworkTransportMapper()

    fun toSnapshot(capabilities: NetworkCapabilities, carrier: NetworkCarrier?) =
        NetworkSnapshot.Connected(
            transport = transportMapper.toTransport(capabilities),
            vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            roaming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
            } else {
                null
            },
            downstreamKbps = capabilities.linkDownstreamBandwidthKbps.takeIf { it > 0 },
            upstreamKbps = capabilities.linkUpstreamBandwidthKbps.takeIf { it > 0 },
            signalStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                capabilities.signalStrength
                    .takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
            } else {
                null
            },
            carrier = carrier
        )
}
