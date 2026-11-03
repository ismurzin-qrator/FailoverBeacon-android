package net.qrator.failoverbeacon.failover.model

internal enum class CheckOutcome {
    PASSED,
    TCP_FAILED,
    TLS_FAILED,
    BODY_FAILED
}
