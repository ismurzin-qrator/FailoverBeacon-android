package net.qrator.failoverbeacon.core.call.model

/**
 * One HTTP request and its response. [resendCount] is 0 for the first request.
 * [attempts] are the connects made for it, none if it used a pooled connection.
 * [serverIp] and [protocol] are null if no connection was obtained.
 */
data class Exchange(
    val request: ExchangeRequest,
    val response: ExchangeResponse?,
    val resendCount: Int,
    val serverIp: String?,
    val protocol: String?,
    val reusedConnection: Boolean,
    val attempts: List<ConnectAttempt>,
    val timings: ExchangeTimings,
    val durationNanos: Long,
    val outcome: ExchangeOutcome
)
