package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot
import net.qrator.failoverbeacon.telemetry.model.TelemetryNetwork

internal class TelemetryNetworkMapper {
    private val transportMapper = TelemetryNetworkTransportMapper()
    private val carrierMapper = TelemetryCarrierMapper()

    fun toNetwork(snapshot: NetworkSnapshot): TelemetryNetwork = when (snapshot) {
        NetworkSnapshot.Unknown -> TelemetryNetwork.Unknown

        NetworkSnapshot.None -> TelemetryNetwork.None

        is NetworkSnapshot.Connected -> TelemetryNetwork.Connected(
            transport = transportMapper.toTransport(snapshot.transport),
            vpn = snapshot.vpn,
            validated = snapshot.validated,
            captivePortal = snapshot.captivePortal,
            metered = snapshot.metered,
            roaming = snapshot.roaming,
            downstreamKbps = snapshot.downstreamKbps,
            upstreamKbps = snapshot.upstreamKbps,
            signalStrength = snapshot.signalStrength,
            carrier = snapshot.carrier?.let(carrierMapper::toCarrier),
            blocked = snapshot.blocked
        )
    }
}
