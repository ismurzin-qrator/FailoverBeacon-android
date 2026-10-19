package net.qrator.failoverbeacon.telemetry.model

/**
 * [step] is where the exchange failed, null if it did not. Durations are in milliseconds.
 * [protocol], [serverIp] and [proxy] are null without a connection, [tls] also for one without TLS.
 * Through a proxy [serverIp] and [dnsAddresses] are the proxy's.
 */
internal data class TelemetryEvent(
    val callId: Long,
    val resendCount: Int,
    val host: String?,
    val port: Int?,
    val method: String?,
    val phase: Phase,
    val type: ErrorType,
    val step: Step?,
    val statusCode: Int?,
    val protocol: String?,
    val serverIp: String?,
    val proxy: ProxyType?,
    val tls: EventTls?,
    val reusedConnection: Boolean,
    val dnsAddresses: List<String>?,
    val elapsedMs: Long,
    val timings: EventTimings,
    val requestBodyBytes: Long?,
    val responseContentLength: Long?,
    val responseBodyBytes: Long?,
    val attempts: List<EventAttempt>
)
