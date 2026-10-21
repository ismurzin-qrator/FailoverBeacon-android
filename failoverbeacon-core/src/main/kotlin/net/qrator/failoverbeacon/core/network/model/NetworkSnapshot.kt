package net.qrator.failoverbeacon.core.network.model

sealed interface NetworkSnapshot {
    data object Unknown : NetworkSnapshot

    data object None : NetworkSnapshot

    /** [roaming] is null below API 28, [signalStrength] below API 29. */
    data class Connected(
        val transport: NetworkTransport,
        val vpn: Boolean,
        val validated: Boolean,
        val captivePortal: Boolean,
        val metered: Boolean,
        val roaming: Boolean?,
        val downstreamKbps: Int?,
        val upstreamKbps: Int?,
        val signalStrength: Int?,
        val carrier: NetworkCarrier?
    ) : NetworkSnapshot
}
