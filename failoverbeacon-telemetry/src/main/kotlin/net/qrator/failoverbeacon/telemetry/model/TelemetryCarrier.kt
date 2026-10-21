package net.qrator.failoverbeacon.telemetry.model

internal data class TelemetryCarrier(
    val name: String?,
    val mcc: String?,
    val mnc: String?,
    val carrierId: Int?,
    val networkType: Int?
)
