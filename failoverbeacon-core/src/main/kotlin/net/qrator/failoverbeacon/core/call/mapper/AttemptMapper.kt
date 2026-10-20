package net.qrator.failoverbeacon.core.call.mapper

import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.state.AttemptState
import net.qrator.failoverbeacon.core.call.state.CallStopReason
import net.qrator.failoverbeacon.core.call.state.ConnectStopReason

internal class AttemptMapper(private val failureMapper: FailureMapper) {
    /** [callStopReason] and [callFailure] tell how the call ended the connects it stopped. */
    fun toResults(
        attempts: List<AttemptState>,
        callStopReason: CallStopReason?,
        callFailure: Throwable?
    ): List<ConnectResult> = attempts.map { attempt ->
        if (attempt.isConnected) return@map ConnectResult.Connected
        val step = attempt.failedStep ?: ExchangeStep.TCP
        val failed = ConnectResult.Failed(step, attempt.failure?.let(failureMapper::toFailureType))
        val superseded = if (isSuperseded(attempt, attempts)) ConnectResult.Superseded else null
        val stoppedWithCall = when (callStopReason) {
            CallStopReason.CANCELED -> ConnectResult.Canceled

            CallStopReason.TIMED_OUT ->
                ConnectResult.Failed(step, callFailure?.let(failureMapper::toFailureType))

            else -> null
        }
        when {
            attempt.isConnecting -> superseded ?: stoppedWithCall ?: failed
            attempt.stopReason == ConnectStopReason.CLIENT -> superseded ?: failed
            attempt.stopReason == ConnectStopReason.CALL -> stoppedWithCall ?: failed
            else -> failed
        }
    }

    private fun isSuperseded(attempt: AttemptState, attempts: List<AttemptState>): Boolean {
        val interval = attempt.connectInterval
        return attempts.any { other ->
            when {
                other === attempt -> false

                // Includes a connect started once the winner had already connected.
                other.isConnected -> interval.endsAfter(other.connectInterval.startNanos)

                other.failedStep == ExchangeStep.TLS -> interval.overlaps(other.connectInterval)

                else -> false
            }
        }
    }
}
