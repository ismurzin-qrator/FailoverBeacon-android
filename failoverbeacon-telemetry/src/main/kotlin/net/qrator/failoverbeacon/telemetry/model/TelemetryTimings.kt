package net.qrator.failoverbeacon.telemetry.model

/** Phase durations in milliseconds, null for a phase that did not happen. Connect includes TLS. */
internal data class TelemetryTimings(
    val dnsMs: Long?,
    val connectMs: Long?,
    val tlsMs: Long?,
    val sendMs: Long?,
    val waitMs: Long?,
    val receiveMs: Long?
)
