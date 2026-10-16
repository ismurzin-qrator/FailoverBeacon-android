package net.qrator.failoverbeacon.telemetry.event

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.FailureType

class ErrorTypeMapperTest {
    private val mapper = ErrorTypeMapper()

    @Test
    fun `the phase follows the step`() {
        val expected = mapOf(
            ExchangeStep.DNS to Phase.DNS,
            ExchangeStep.TCP to Phase.CONNECTION,
            ExchangeStep.TLS to Phase.CONNECTION,
            ExchangeStep.REQUEST to Phase.APPLICATION,
            ExchangeStep.RESPONSE to Phase.APPLICATION
        )

        assertEquals(expected, expected.mapValues { (step, _) -> mapper.toPhase(step) })
        assertEquals(Phase.APPLICATION, mapper.toPhase(null))
    }

    @Test
    fun `failure types map to error types`() {
        val expected = mapOf(
            FailureType.NAME_NOT_RESOLVED to ErrorType.DNS_NAME_NOT_RESOLVED,
            FailureType.TIMED_OUT to ErrorType.TCP_TIMED_OUT,
            FailureType.CLOSED to ErrorType.TCP_CLOSED,
            FailureType.RESET to ErrorType.TCP_RESET,
            FailureType.REFUSED to ErrorType.TCP_REFUSED,
            FailureType.ABORTED to ErrorType.TCP_ABORTED,
            FailureType.ADDRESS_INVALID to ErrorType.TCP_ADDRESS_INVALID,
            FailureType.ADDRESS_UNREACHABLE to ErrorType.TCP_ADDRESS_UNREACHABLE,
            FailureType.CERT_UNTRUSTED to ErrorType.TLS_CERT_AUTHORITY_INVALID,
            FailureType.CERT_NAME_MISMATCH to ErrorType.TLS_CERT_NAME_INVALID,
            FailureType.CERT_PIN_MISMATCH to ErrorType.TLS_CERT_PINNED_KEY_NOT_IN_CERT_CHAIN,
            FailureType.TLS_PROTOCOL_ERROR to ErrorType.TLS_PROTOCOL_ERROR,
            FailureType.PROTOCOL_ERROR to ErrorType.HTTP_PROTOCOL_ERROR,
            FailureType.STREAM_RESET to ErrorType.HTTP_PROTOCOL_ERROR,
            FailureType.OTHER to ErrorType.HTTP_FAILED
        )

        val actual = expected.mapValues { (type, _) ->
            mapper.toErrorType(ExchangeStep.REQUEST, type)
        }

        assertEquals(expected, actual)
    }

    @Test
    fun `other failures are named by their step`() {
        val expected = mapOf(
            ExchangeStep.DNS to ErrorType.DNS_FAILED,
            ExchangeStep.TCP to ErrorType.TCP_FAILED,
            ExchangeStep.TLS to ErrorType.TLS_FAILED,
            ExchangeStep.REQUEST to ErrorType.HTTP_FAILED,
            ExchangeStep.RESPONSE to ErrorType.HTTP_FAILED
        )

        val actual = expected.mapValues { (step, _) -> mapper.toErrorType(step, FailureType.OTHER) }

        assertEquals(expected, actual)
        assertEquals(ErrorType.UNKNOWN, mapper.toErrorType(null, FailureType.OTHER))
    }

    @Test
    fun `a protocol error in the response is an invalid response`() {
        assertEquals(
            ErrorType.HTTP_RESPONSE_INVALID,
            mapper.toErrorType(ExchangeStep.RESPONSE, FailureType.PROTOCOL_ERROR)
        )
    }

    @Test
    fun `a failure without a type is unknown`() {
        assertEquals(ErrorType.UNKNOWN, mapper.toErrorType(ExchangeStep.TCP, null))
        assertEquals(ErrorType.UNKNOWN, mapper.toErrorType(null, null))
    }

    @Test
    fun `error type codes are unique`() {
        val codes = ErrorType.entries.map { it.code }

        assertEquals(codes.size, codes.toSet().size)
    }
}
