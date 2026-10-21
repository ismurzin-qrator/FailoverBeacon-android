package net.qrator.failoverbeacon.telemetry.model

internal sealed interface TelemetryNetwork {
    data object Unknown : TelemetryNetwork

    data object None : TelemetryNetwork

    data class Connected(
        val transport: TelemetryNetworkTransport,
        val vpn: Boolean,
        val validated: Boolean,
        val captivePortal: Boolean,
        val metered: Boolean,
        val roaming: Boolean?,
        val downstreamKbps: Int?,
        val upstreamKbps: Int?,
        val signalStrength: Int?,
        val carrier: TelemetryCarrier?
    ) : TelemetryNetwork
}
