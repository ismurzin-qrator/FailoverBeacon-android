package net.qrator.failoverbeacon.core.call.model

/**
 * One try to connect to [peerAddress] for an exchange. Through a proxy [peerAddress] is
 * the proxy's: an IP for HTTP, the server's name for SOCKS. [connectNanos] includes TLS.
 */
data class ConnectAttempt(
    val peerAddress: String,
    val proxy: ProxyType,
    val connectNanos: Long?,
    val result: ConnectResult
)
