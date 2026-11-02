package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

internal data class DnsState(
    val addresses: List<InetAddress> = emptyList(),
    private val lastAttempt: ResolveAttempt? = null
) {
    val hasAnswer: Boolean get() = addresses.isNotEmpty()

    fun isFresh(timer: RefreshTimer): Boolean =
        lastAttempt is ResolveAttempt.Answered && !timer.isExpired(lastAttempt.atNanos)

    fun isStale(timer: RefreshTimer): Boolean =
        lastAttempt == null || timer.isExpired(lastAttempt.atNanos)

    fun isAnswerNeeded(timer: RefreshTimer): Boolean =
        lastAttempt !is ResolveAttempt.Failed && !isFresh(timer)

    fun withAnswer(addresses: List<InetAddress>, atNanos: Long): DnsState =
        DnsState(addresses, ResolveAttempt.Answered(atNanos))

    fun withFailure(atNanos: Long): DnsState = copy(lastAttempt = ResolveAttempt.Failed(atNanos))
}
