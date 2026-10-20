package net.qrator.failoverbeacon.telemetry.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.telemetry.model.ErrorType

class ErrorTypeMapperTest {
    private val mapper = ErrorTypeMapper()

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
            FailureType.REDIRECT_LOOP to ErrorType.HTTP_RESPONSE_REDIRECT_LOOP,
            FailureType.STREAM_RESET to ErrorType.HTTP_PROTOCOL_ERROR,
            FailureType.OTHER to ErrorType.HTTP_FAILED
        )

        val actual = expected.mapValues { (type, _) ->
            mapper.toErrorType(ExchangeStep.REQUEST, type, response = null)
        }

        assertEquals(expected, actual)
    }

    @Test
    fun `other and untyped failures are named by their step`() {
        val expected = mapOf(
            ExchangeStep.DNS to ErrorType.DNS_FAILED,
            ExchangeStep.TCP to ErrorType.TCP_FAILED,
            ExchangeStep.TLS to ErrorType.TLS_FAILED,
            ExchangeStep.REQUEST to ErrorType.HTTP_FAILED,
            ExchangeStep.RESPONSE to ErrorType.HTTP_FAILED
        )

        listOf(FailureType.OTHER, null).forEach { type ->
            val actual = expected.mapValues { (step, _) -> toErrorType(step, type) }

            assertEquals(expected, actual, "$type")
            assertEquals(ErrorType.UNKNOWN, toErrorType(null, type), "$type")
        }
    }

    @Test
    fun `a timeout during DNS is a DNS timeout`() {
        assertEquals(ErrorType.DNS_TIMED_OUT, toErrorType(ExchangeStep.DNS, FailureType.TIMED_OUT))
    }

    @Test
    fun `a response cut short is named by what arrived`() {
        val withLength = ExchangeResponse(200, contentLength = 100, bodyBytes = null)
        val chunked = ExchangeResponse(200, contentLength = null, bodyBytes = null)

        assertEquals(
            ErrorType.HTTP_RESPONSE_INVALID_EMPTY,
            mapper.toErrorType(ExchangeStep.RESPONSE, FailureType.CLOSED, response = null)
        )
        assertEquals(
            ErrorType.HTTP_RESPONSE_INVALID_CONTENT_LENGTH_MISMATCH,
            mapper.toErrorType(ExchangeStep.RESPONSE, FailureType.CLOSED, withLength)
        )
        assertEquals(
            ErrorType.HTTP_RESPONSE_INVALID_INCOMPLETE_CHUNKED_ENCODING,
            mapper.toErrorType(ExchangeStep.RESPONSE, FailureType.CLOSED, chunked)
        )
    }

    @Test
    fun `a protocol error in the response is an invalid response`() {
        assertEquals(
            ErrorType.HTTP_RESPONSE_INVALID,
            toErrorType(ExchangeStep.RESPONSE, FailureType.PROTOCOL_ERROR)
        )
    }

    @Test
    fun `error type codes are unique`() {
        val codes = ErrorType.entries.map { it.code }

        assertEquals(codes.size, codes.toSet().size)
    }

    private fun toErrorType(step: ExchangeStep?, type: FailureType?) =
        mapper.toErrorType(step, type, response = null)
}
