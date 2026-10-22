package net.qrator.failoverbeacon.telemetry.model

internal enum class TelemetryPhase(val code: String) {
    DNS("dns"),
    CONNECTION("connection"),
    APPLICATION("application")
}
