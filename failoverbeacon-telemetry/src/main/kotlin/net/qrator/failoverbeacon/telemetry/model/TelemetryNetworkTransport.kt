package net.qrator.failoverbeacon.telemetry.model

internal enum class TelemetryNetworkTransport(val code: String) {
    WIFI("wifi"),
    ETHERNET("ethernet"),
    CELLULAR("cellular"),
    BLUETOOTH("bluetooth"),
    OTHER("other")
}
