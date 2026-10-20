package net.qrator.failoverbeacon.telemetry.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TelemetryErrorTypeTest {
    @Test
    fun `every error type has the phase NEL groups it under`() {
        TelemetryErrorType.entries.forEach { type ->
            val expected = when (type.code.substringBefore('.')) {
                "dns" -> TelemetryPhase.DNS
                "tcp", "tls" -> TelemetryPhase.CONNECTION
                else -> TelemetryPhase.APPLICATION
            }
            assertEquals(expected, type.phase, type.code)
        }
    }
}
