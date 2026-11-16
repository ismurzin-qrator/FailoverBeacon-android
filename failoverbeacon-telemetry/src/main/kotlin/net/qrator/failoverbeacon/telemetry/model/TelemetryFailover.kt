package net.qrator.failoverbeacon.telemetry.model

internal enum class TelemetryFailover(val code: String) {
    ON("on"),
    DNS_FAILED("dns_failed"),
    RETRY("retry")
}
