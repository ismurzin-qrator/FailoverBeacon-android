package net.qrator.failoverbeacon.telemetry.model

/**
 * [step] is where the exchange failed, null if it did not. Durations are in milliseconds.
 * [protocol], [serverIp] and [proxy] are null without a connection, [tls] also for one without TLS.
 * Through an HTTP proxy [serverIp] and [dnsAddresses] are the proxy's.
 * Through SOCKS [serverIp] is the server's name and [dnsAddresses] is null.
 */
internal data class TelemetryEvent(
    val callId: Long,
    val resendCount: Int,
    val scheme: String?,
    val host: String?,
    val port: Int?,
    val method: String?,
    val phase: TelemetryPhase,
    val type: TelemetryErrorType,
    val step: TelemetryStep?,
    val statusCode: Int?,
    val protocol: String?,
    val serverIp: String?,
    val proxy: TelemetryProxyType?,
    val tls: TelemetryTls?,
    val reusedConnection: Boolean,
    val dnsAddresses: List<String>?,
    val startTimeMillis: Long,
    val elapsedMs: Long,
    val timings: TelemetryTimings,
    val requestBodyBytes: Long?,
    val responseContentLength: Long?,
    val responseBodyBytes: Long?,
    val attempts: List<TelemetryAttempt>,
    val network: TelemetryNetwork,
    val foreground: Boolean?
)
