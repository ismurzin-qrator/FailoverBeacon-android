package net.qrator.failoverbeacon.core.call.model

import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot

/**
 * One HTTP request and its response.
 * [callId] is the same for every exchange of one call.
 * [resendCount] is 0 for the first request.
 * [connection] is null if none was obtained.
 * [dnsAddresses] are those of the last lookup.
 * [attempts] are the connects made for it, none if it used a pooled connection.
 */
data class Exchange(
    val callId: Long,
    val request: ExchangeRequest,
    val response: ExchangeResponse?,
    val resendCount: Int,
    val connection: ExchangeConnection?,
    val reusedConnection: Boolean,
    val dnsAddresses: List<String>?,
    val attempts: List<ConnectAttempt>,
    val timings: ExchangeTimings,
    val durationNanos: Long,
    val outcome: ExchangeOutcome,
    val network: NetworkSnapshot
)
