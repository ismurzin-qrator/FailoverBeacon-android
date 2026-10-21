package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.network.model.NetworkCarrier
import net.qrator.failoverbeacon.telemetry.model.TelemetryCarrier

internal class TelemetryCarrierMapper {
    fun toCarrier(carrier: NetworkCarrier): TelemetryCarrier = TelemetryCarrier(
        name = carrier.name,
        mcc = carrier.mcc,
        mnc = carrier.mnc,
        carrierId = carrier.carrierId,
        networkType = carrier.networkType
    )
}
