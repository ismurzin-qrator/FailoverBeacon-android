package net.qrator.failoverbeacon.telemetry.model

/** Steps of an exchange in the order they happen. */
internal enum class TelemetryStep(val code: String) {
    DNS("dns"),
    TCP("tcp"),
    TLS("tls"),
    REQUEST("request"),
    RESPONSE("response")
}
