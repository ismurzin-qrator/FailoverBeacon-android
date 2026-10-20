package net.qrator.failoverbeacon.core.call.mapper

import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.state.CallStopReason
import net.qrator.failoverbeacon.core.call.state.ExchangeState

internal class OutcomeMapper(private val failureMapper: FailureMapper) {
    /** [results] are the results of the exchange's attempts, in the same order. */
    fun toOutcome(state: ExchangeState, results: List<ConnectResult>): ExchangeOutcome =
        with(state) {
            val cause = exchangeFailure
            val failedConnecting = connection == null &&
                (cause == null || attempts.any { it.failure === cause })
            val connectFailure = if (failedConnecting) furthestFailure(results) else null
            when {
                stopReason == CallStopReason.CANCELED -> ExchangeOutcome.Canceled(lastStep)

                connectFailure != null ->
                    ExchangeOutcome.Failed(connectFailure.step, connectFailure.type)

                cause != null -> ExchangeOutcome.Failed(
                    lastStep,
                    failureMapper.toFailureType(cause)
                )

                responded -> ExchangeOutcome.Ok

                else -> ExchangeOutcome.Failed(lastStep, type = null)
            }
        }

    // A failure after the response and before the next request is not the exchange's, unless it
    // rejects the response. A call timeout replaces the failure it caused.
    private val ExchangeState.exchangeFailure: Throwable?
        get() = when {
            failure != null && callStopReason == CallStopReason.TIMED_OUT -> callFailure
            failure != null -> failure
            completed && callStopReason != CallStopReason.RESPONSE_REJECTED -> null
            else -> callFailure
        }

    // The furthest failed connect, then the last to end: clients differ in which one fails the call.
    private fun ExchangeState.furthestFailure(results: List<ConnectResult>): ConnectResult.Failed? {
        val failed = attempts.zip(results)
            .sortedBy { (attempt, _) -> attempt.connectInterval.endNanos }
            .map { (_, result) -> result }
            .filterIsInstance<ConnectResult.Failed>()
        return failed.lastOrNull { it.step == ExchangeStep.TLS } ?: failed.lastOrNull()
    }
}
