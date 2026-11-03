package net.qrator.failoverbeacon.failover.model

internal enum class ConnectOutcome {
    CONNECTED_PLAIN,
    CONNECTED_SECURE,
    TCP_FAILED,
    TLS_FAILED
}
