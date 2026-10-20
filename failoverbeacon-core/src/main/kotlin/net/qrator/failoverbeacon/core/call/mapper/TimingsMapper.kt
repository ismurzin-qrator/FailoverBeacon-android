package net.qrator.failoverbeacon.core.call.mapper

import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.core.call.state.ExchangeState

internal class TimingsMapper {
    fun toTimings(state: ExchangeState): ExchangeTimings = with(state) {
        ExchangeTimings(
            dnsNanos = dnsInterval.durationNanos,
            connectNanos = connectedAttempt?.connectInterval?.durationNanos,
            tlsNanos = tlsInterval.durationNanos,
            sendNanos = sendInterval.durationNanos,
            waitNanos = waitInterval.durationNanos.takeUnless { requestUnsent },
            receiveNanos = receiveInterval.durationNanos.takeUnless { endsAtHeaders }
        )
    }

    fun toDurationNanos(state: ExchangeState): Long = with(state) {
        val endNanos = when {
            endsAtHeaders -> waitInterval.endNanos

            // A DNS lookup has no event when it fails.
            lastStep == ExchangeStep.DNS && dnsInterval.endNanos == null -> closedNanos

            else -> lastEventNanos
        }
        endNanos?.minus(startNanos)?.takeIf { it >= 0 } ?: 0
    }

    // The app gets the body of the last response only, unless the call fails after it.
    private val ExchangeState.endsAtHeaders: Boolean
        get() = responded && (followedUp || (completed && callFailure != null))

    private val ExchangeState.lastEventNanos: Long
        get() = (
            listOf(dnsInterval, tlsInterval, sendInterval, waitInterval, receiveInterval) +
                attempts.map { it.connectInterval }
            )
            .flatMap { listOf(it.startNanos, it.endNanos) }
            .fold(startNanos) { last, nanos -> maxOf(last, nanos ?: last) }
}
