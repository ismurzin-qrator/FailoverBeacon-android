package net.qrator.failoverbeacon.core.call.model

/** One try to connect to [peerAddress] for an exchange. [connectNanos] includes TLS. */
data class ConnectAttempt(
    val peerAddress: String,
    val connectNanos: Long?,
    val result: ConnectResult
)
