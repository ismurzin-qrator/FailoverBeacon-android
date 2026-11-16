package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

internal data class DnsState(
    val addresses: List<InetAddress> = emptyList(),
    val answeredAtMillis: Long? = null,
    private val failedAtMillis: Long? = null
) {
    val hasAnswer: Boolean get() = addresses.isNotEmpty()
    val hasFailed: Boolean get() = failedAtMillis != null

    fun isFresh(maxAge: MaxAge): Boolean =
        failedAtMillis == null && answeredAtMillis != null && !maxAge.isOver(answeredAtMillis)

    fun isResolveDue(maxAge: MaxAge): Boolean {
        val lastAttemptAtMillis = failedAtMillis ?: answeredAtMillis
        return lastAttemptAtMillis == null || maxAge.isOver(lastAttemptAtMillis)
    }

    fun isAnswerNeeded(maxAge: MaxAge): Boolean = failedAtMillis == null && !isFresh(maxAge)

    fun withAnswer(addresses: List<InetAddress>, atMillis: Long): DnsState =
        DnsState(addresses, answeredAtMillis = atMillis)

    fun withFailure(atMillis: Long): DnsState = copy(failedAtMillis = atMillis)
}
