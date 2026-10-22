package net.qrator.failoverbeacon.telemetry.filter

import net.qrator.failoverbeacon.telemetry.model.TelemetryAttemptResult
import net.qrator.failoverbeacon.telemetry.model.TelemetryErrorType
import net.qrator.failoverbeacon.telemetry.model.TelemetryEvent

internal class Sampler(
    private val failureSampleRate: Double,
    private val successSampleRate: Double
) {
    fun sample(event: TelemetryEvent): Double? {
        val fraction = if (isSuccess(event)) successSampleRate else failureSampleRate
        return fraction.takeIf { roll(event.callId) < it }
    }

    private fun isSuccess(event: TelemetryEvent): Boolean = event.type == TelemetryErrorType.OK &&
        event.attempts.none { it.result == TelemetryAttemptResult.FAILED }

    // Uniform in [0, 1): the top 53 bits of the random call id.
    private fun roll(callId: Long): Double = (callId ushr DOUBLE_SHIFT) * DOUBLE_UNIT

    private companion object {
        const val DOUBLE_SHIFT = 11
        const val DOUBLE_UNIT = 1.0 / (1L shl 53)
    }
}
