package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

internal data class DnsState(
    val addresses: List<InetAddress> = emptyList(),
    private val lastAttempt: ResolveAttempt? = null,
    val answeredAtMillis: Long? = null
) {
    val hasAnswer: Boolean get() = addresses.isNotEmpty()

    fun isFresh(timer: RefreshTimer): Boolean =
        lastAttempt is ResolveAttempt.Answered && !timer.isExpired(lastAttempt.atMillis)

    fun isStale(timer: RefreshTimer): Boolean =
        lastAttempt == null || timer.isExpired(lastAttempt.atMillis)

    fun isAnswerNeeded(timer: RefreshTimer): Boolean =
        lastAttempt !is ResolveAttempt.Failed && !isFresh(timer)

    fun withAnswer(addresses: List<InetAddress>, atMillis: Long): DnsState =
        DnsState(addresses, ResolveAttempt.Answered(atMillis), atMillis)

    fun withFailure(atMillis: Long): DnsState = copy(lastAttempt = ResolveAttempt.Failed(atMillis))
}
