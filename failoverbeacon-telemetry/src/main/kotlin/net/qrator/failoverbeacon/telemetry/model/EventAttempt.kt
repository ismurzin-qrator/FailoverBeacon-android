package net.qrator.failoverbeacon.telemetry.model

/** [type] and [step] are set if it failed. */
internal data class EventAttempt(
    val ip: String,
    val ms: Long?,
    val result: AttemptResult,
    val type: ErrorType?,
    val step: Step?
)
