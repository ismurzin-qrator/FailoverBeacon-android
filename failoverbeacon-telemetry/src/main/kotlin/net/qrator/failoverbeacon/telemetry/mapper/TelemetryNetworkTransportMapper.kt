package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.network.model.NetworkTransport
import net.qrator.failoverbeacon.telemetry.model.TelemetryNetworkTransport

internal class TelemetryNetworkTransportMapper {
    fun toTransport(transport: NetworkTransport): TelemetryNetworkTransport = when (transport) {
        NetworkTransport.WIFI -> TelemetryNetworkTransport.WIFI
        NetworkTransport.ETHERNET -> TelemetryNetworkTransport.ETHERNET
        NetworkTransport.CELLULAR -> TelemetryNetworkTransport.CELLULAR
        NetworkTransport.BLUETOOTH -> TelemetryNetworkTransport.BLUETOOTH
        NetworkTransport.OTHER -> TelemetryNetworkTransport.OTHER
    }
}
