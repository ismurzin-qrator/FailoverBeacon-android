package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.telemetry.model.ErrorType

internal class ErrorTypeMapper {
    fun toErrorType(
        step: ExchangeStep?,
        type: FailureType?,
        response: ExchangeResponse?
    ): ErrorType = when (type) {
        null -> ErrorType.UNKNOWN
        FailureType.NAME_NOT_RESOLVED -> ErrorType.DNS_NAME_NOT_RESOLVED
        FailureType.TIMED_OUT -> timedOut(step)
        FailureType.CLOSED -> closed(step, response)
        FailureType.RESET -> ErrorType.TCP_RESET
        FailureType.REFUSED -> ErrorType.TCP_REFUSED
        FailureType.ABORTED -> ErrorType.TCP_ABORTED
        FailureType.ADDRESS_INVALID -> ErrorType.TCP_ADDRESS_INVALID
        FailureType.ADDRESS_UNREACHABLE -> ErrorType.TCP_ADDRESS_UNREACHABLE
        FailureType.CERT_UNTRUSTED -> ErrorType.TLS_CERT_AUTHORITY_INVALID
        FailureType.CERT_NAME_MISMATCH -> ErrorType.TLS_CERT_NAME_INVALID
        FailureType.CERT_PIN_MISMATCH -> ErrorType.TLS_CERT_PINNED_KEY_NOT_IN_CERT_CHAIN
        FailureType.TLS_PROTOCOL_ERROR -> ErrorType.TLS_PROTOCOL_ERROR
        FailureType.PROTOCOL_ERROR -> protocolError(step)
        FailureType.REDIRECT_LOOP -> ErrorType.HTTP_RESPONSE_REDIRECT_LOOP
        FailureType.STREAM_RESET -> ErrorType.HTTP_PROTOCOL_ERROR
        FailureType.OTHER -> otherError(step)
    }

    private fun timedOut(step: ExchangeStep?): ErrorType = when (step) {
        ExchangeStep.DNS -> ErrorType.DNS_TIMED_OUT
        else -> ErrorType.TCP_TIMED_OUT
    }

    private fun closed(step: ExchangeStep?, response: ExchangeResponse?): ErrorType = when {
        step != ExchangeStep.RESPONSE -> ErrorType.TCP_CLOSED
        response == null -> ErrorType.HTTP_RESPONSE_INVALID_EMPTY
        response.contentLength != null -> ErrorType.HTTP_RESPONSE_INVALID_CONTENT_LENGTH_MISMATCH
        else -> ErrorType.HTTP_RESPONSE_INVALID_INCOMPLETE_CHUNKED_ENCODING
    }

    private fun protocolError(step: ExchangeStep?): ErrorType = when (step) {
        ExchangeStep.RESPONSE -> ErrorType.HTTP_RESPONSE_INVALID
        else -> ErrorType.HTTP_PROTOCOL_ERROR
    }

    private fun otherError(step: ExchangeStep?): ErrorType = when (step) {
        ExchangeStep.DNS -> ErrorType.DNS_FAILED
        ExchangeStep.TCP -> ErrorType.TCP_FAILED
        ExchangeStep.TLS -> ErrorType.TLS_FAILED
        ExchangeStep.REQUEST, ExchangeStep.RESPONSE -> ErrorType.HTTP_FAILED
        null -> ErrorType.UNKNOWN
    }
}
