package net.qrator.failoverbeacon.telemetry.model

internal enum class TelemetryAttemptResult(val code: String) {
    CONNECTED("connected"),
    FAILED("failed"),

    /** The client stopped trying before the network answered. */
    CANCELED("canceled")
}
