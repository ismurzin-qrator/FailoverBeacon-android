package net.qrator.failoverbeacon.failover.model

internal sealed interface ResolveAttempt {
    val atNanos: Long

    data class Answered(override val atNanos: Long) : ResolveAttempt

    data class Failed(override val atNanos: Long) : ResolveAttempt
}
