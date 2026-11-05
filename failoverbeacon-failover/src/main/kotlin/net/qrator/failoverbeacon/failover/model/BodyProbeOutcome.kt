package net.qrator.failoverbeacon.failover.model

internal enum class BodyProbeOutcome {
    PASSED,
    TCP_FAILED,
    TLS_FAILED,
    BODY_FAILED
}
