package net.qrator.failoverbeacon.telemetry.model

/** [step] is where the exchange failed, null if it did not. Durations are in milliseconds. */
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
    val reusedConnection: Boolean,
    val elapsedMs: Long,
    val timings: EventTimings,
    val requestBodyBytes: Long?,
    val responseContentLength: Long?,
    val responseBodyBytes: Long?,
    val attempts: List<EventAttempt>
)
