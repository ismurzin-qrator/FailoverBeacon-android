package net.qrator.failoverbeacon.core.call.model

/** Phase durations, null for a phase that did not happen or did not end. Connect includes TLS. */
data class ExchangeTimings(
    val dnsNanos: Long?,
    val connectNanos: Long?,
    val tlsNanos: Long?,
    val sendNanos: Long?,
    val waitNanos: Long?,
    val receiveNanos: Long?
)
