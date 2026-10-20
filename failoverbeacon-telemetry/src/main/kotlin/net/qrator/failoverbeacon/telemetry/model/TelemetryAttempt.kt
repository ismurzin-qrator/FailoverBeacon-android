package net.qrator.failoverbeacon.telemetry.model

/**
 * [type] and [step] are set if it failed.
 * Through an HTTP proxy [ip] is the proxy's, through SOCKS the server's name.
 */
internal data class TelemetryAttempt(
    val ip: String,
    val proxy: TelemetryProxyType,
    val ms: Long?,
    val result: TelemetryAttemptResult,
    val type: TelemetryErrorType?,
    val step: TelemetryStep?
)
