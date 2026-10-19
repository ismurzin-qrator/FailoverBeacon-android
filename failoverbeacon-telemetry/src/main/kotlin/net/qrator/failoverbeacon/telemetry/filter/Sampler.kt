package net.qrator.failoverbeacon.telemetry.filter

import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome

internal class Sampler(
    private val failureSampleRate: Double,
    private val successSampleRate: Double
) {

    fun fraction(exchange: Exchange): Double =
        if (isSuccess(exchange)) successSampleRate else failureSampleRate

    fun keeps(exchange: Exchange): Boolean = roll(exchange.callId) < fraction(exchange)

    private fun isSuccess(exchange: Exchange): Boolean {
        val statusCode = exchange.response?.statusCode ?: return false
        return exchange.outcome == ExchangeOutcome.Ok &&
            statusCode < MIN_ERROR_STATUS &&
            exchange.attempts.none { it.result is ConnectResult.Failed }
    }

    // Uniform in [0, 1): the top 53 bits of the random call id.
    private fun roll(callId: Long): Double = (callId ushr DOUBLE_SHIFT) * DOUBLE_UNIT

    private companion object {
        const val MIN_ERROR_STATUS = 400
        const val DOUBLE_SHIFT = 11
        const val DOUBLE_UNIT = 1.0 / (1L shl 53)
    }
}
