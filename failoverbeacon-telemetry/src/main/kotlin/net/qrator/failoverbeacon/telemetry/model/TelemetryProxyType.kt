package net.qrator.failoverbeacon.telemetry.model

internal enum class TelemetryProxyType(val code: String) {
    DIRECT("direct"),
    HTTP("http"),
    SOCKS("socks")
}
