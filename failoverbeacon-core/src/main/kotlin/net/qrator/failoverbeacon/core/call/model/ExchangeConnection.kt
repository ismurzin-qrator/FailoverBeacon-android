package net.qrator.failoverbeacon.core.call.model

/**
 * The connection an exchange was sent on. Through an HTTP proxy [peerAddress] is the proxy's IP.
 * Through SOCKS the server's name.
 * [protocol] is its ALPN ID.
 */
data class ExchangeConnection(
    val peerAddress: String,
    val protocol: String,
    val proxy: ProxyType,
    val tls: ExchangeTls?
)
