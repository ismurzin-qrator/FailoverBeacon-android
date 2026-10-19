package net.qrator.failoverbeacon.core.call.model

/**
 * One try to connect to [peerAddress] for an exchange. Through an HTTP proxy [peerAddress] is
 * the proxy's IP, through SOCKS the server's name. [connectNanos] includes TLS.
 */
data class ConnectAttempt(
    val peerAddress: String,
    val proxy: ProxyType,
    val connectNanos: Long?,
    val result: ConnectResult
)
