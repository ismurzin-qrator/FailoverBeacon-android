package net.qrator.failoverbeacon.telemetry.model

/** [type] and [step] are set if it failed. Through a proxy [ip] is the proxy's. */
internal data class EventAttempt(
    val ip: String,
    val proxy: ProxyType,
    val ms: Long?,
    val result: AttemptResult,
    val type: ErrorType?,
    val step: Step?
)
