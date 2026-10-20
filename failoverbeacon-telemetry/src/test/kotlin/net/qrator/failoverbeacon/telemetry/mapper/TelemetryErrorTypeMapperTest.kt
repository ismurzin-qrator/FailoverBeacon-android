package net.qrator.failoverbeacon.telemetry.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.telemetry.model.TelemetryErrorType

class TelemetryErrorTypeMapperTest {
    private val mapper = TelemetryErrorTypeMapper()

    @Test
    fun `failure types map to error types`() {
        val expected = mapOf(
            FailureType.NAME_NOT_RESOLVED to TelemetryErrorType.DNS_NAME_NOT_RESOLVED,
            FailureType.TIMED_OUT to TelemetryErrorType.TCP_TIMED_OUT,
            FailureType.CLOSED to TelemetryErrorType.TCP_CLOSED,
            FailureType.RESET to TelemetryErrorType.TCP_RESET,
            FailureType.REFUSED to TelemetryErrorType.TCP_REFUSED,
            FailureType.ABORTED to TelemetryErrorType.TCP_ABORTED,
            FailureType.ADDRESS_INVALID to TelemetryErrorType.TCP_ADDRESS_INVALID,
            FailureType.ADDRESS_UNREACHABLE to TelemetryErrorType.TCP_ADDRESS_UNREACHABLE,
            FailureType.CERT_UNTRUSTED to TelemetryErrorType.TLS_CERT_AUTHORITY_INVALID,
            FailureType.CERT_NAME_MISMATCH to TelemetryErrorType.TLS_CERT_NAME_INVALID,
            FailureType.CERT_PIN_MISMATCH to
                TelemetryErrorType.TLS_CERT_PINNED_KEY_NOT_IN_CERT_CHAIN,
            FailureType.TLS_PROTOCOL_ERROR to TelemetryErrorType.TLS_PROTOCOL_ERROR,
            FailureType.PROTOCOL_ERROR to TelemetryErrorType.HTTP_PROTOCOL_ERROR,
            FailureType.REDIRECT_LOOP to TelemetryErrorType.HTTP_RESPONSE_REDIRECT_LOOP,
            FailureType.STREAM_RESET to TelemetryErrorType.HTTP_PROTOCOL_ERROR,
            FailureType.OTHER to TelemetryErrorType.HTTP_FAILED
        )

        val actual = expected.mapValues { (type, _) ->
            mapper.toErrorType(ExchangeStep.REQUEST, type, response = null)
        }

        assertEquals(expected, actual)
    }

    @Test
    fun `other and untyped failures are named by their step`() {
        val expected = mapOf(
            ExchangeStep.DNS to TelemetryErrorType.DNS_FAILED,
            ExchangeStep.TCP to TelemetryErrorType.TCP_FAILED,
            ExchangeStep.TLS to TelemetryErrorType.TLS_FAILED,
            ExchangeStep.REQUEST to TelemetryErrorType.HTTP_FAILED,
            ExchangeStep.RESPONSE to TelemetryErrorType.HTTP_FAILED
        )

        listOf(FailureType.OTHER, null).forEach { type ->
            val actual = expected.mapValues { (step, _) -> toErrorType(step, type) }

            assertEquals(expected, actual, "$type")
            assertEquals(TelemetryErrorType.UNKNOWN, toErrorType(null, type), "$type")
        }
    }

    @Test
    fun `a timeout during DNS is a DNS timeout`() {
        assertEquals(
            TelemetryErrorType.DNS_TIMED_OUT,
            toErrorType(ExchangeStep.DNS, FailureType.TIMED_OUT)
        )
    }

    @Test
    fun `a response cut short is named by what arrived`() {
        val withLength = ExchangeResponse(200, contentLength = 100, bodyBytes = null)
        val chunked = ExchangeResponse(200, contentLength = null, bodyBytes = null)

        assertEquals(
            TelemetryErrorType.HTTP_RESPONSE_INVALID_EMPTY,
            mapper.toErrorType(ExchangeStep.RESPONSE, FailureType.CLOSED, response = null)
        )
        assertEquals(
            TelemetryErrorType.HTTP_RESPONSE_INVALID_CONTENT_LENGTH_MISMATCH,
            mapper.toErrorType(ExchangeStep.RESPONSE, FailureType.CLOSED, withLength)
        )
        assertEquals(
            TelemetryErrorType.HTTP_RESPONSE_INVALID_INCOMPLETE_CHUNKED_ENCODING,
            mapper.toErrorType(ExchangeStep.RESPONSE, FailureType.CLOSED, chunked)
        )
    }

    @Test
    fun `a protocol error in the response is an invalid response`() {
        assertEquals(
            TelemetryErrorType.HTTP_RESPONSE_INVALID,
            toErrorType(ExchangeStep.RESPONSE, FailureType.PROTOCOL_ERROR)
        )
    }

    @Test
    fun `error type codes are unique`() {
        val codes = TelemetryErrorType.entries.map { it.code }

        assertEquals(codes.size, codes.toSet().size)
    }

    private fun toErrorType(step: ExchangeStep?, type: FailureType?) =
        mapper.toErrorType(step, type, response = null)
}
