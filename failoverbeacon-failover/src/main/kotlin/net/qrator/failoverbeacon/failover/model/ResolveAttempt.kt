package net.qrator.failoverbeacon.failover.model

internal sealed interface ResolveAttempt {
    val atMillis: Long

    data class Answered(override val atMillis: Long) : ResolveAttempt

    data class Failed(override val atMillis: Long) : ResolveAttempt
}
