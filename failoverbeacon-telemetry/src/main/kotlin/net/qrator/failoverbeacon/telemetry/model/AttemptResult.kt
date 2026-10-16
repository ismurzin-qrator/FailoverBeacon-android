package net.qrator.failoverbeacon.telemetry.model

internal enum class AttemptResult {
    CONNECTED,
    FAILED,

    /** The client stopped trying before the network answered. */
    CANCELED
}
