package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.telemetry.model.TelemetryErrorType

internal class TelemetryErrorTypeMapper {
    fun toErrorType(
        step: ExchangeStep?,
        type: FailureType?,
        response: ExchangeResponse?
    ): TelemetryErrorType = when (type) {
        FailureType.NAME_NOT_RESOLVED -> TelemetryErrorType.DNS_NAME_NOT_RESOLVED
        FailureType.TIMED_OUT -> timedOut(step)
        FailureType.CLOSED -> closed(step, response)
        FailureType.RESET -> TelemetryErrorType.TCP_RESET
        FailureType.REFUSED -> TelemetryErrorType.TCP_REFUSED
        FailureType.ABORTED -> TelemetryErrorType.TCP_ABORTED
        FailureType.ADDRESS_INVALID -> TelemetryErrorType.TCP_ADDRESS_INVALID
        FailureType.ADDRESS_UNREACHABLE -> TelemetryErrorType.TCP_ADDRESS_UNREACHABLE
        FailureType.CERT_UNTRUSTED -> TelemetryErrorType.TLS_CERT_AUTHORITY_INVALID
        FailureType.CERT_NAME_MISMATCH -> TelemetryErrorType.TLS_CERT_NAME_INVALID
        FailureType.CERT_PIN_MISMATCH -> TelemetryErrorType.TLS_CERT_PINNED_KEY_NOT_IN_CERT_CHAIN
        FailureType.TLS_PROTOCOL_ERROR -> TelemetryErrorType.TLS_PROTOCOL_ERROR
        FailureType.PROTOCOL_ERROR -> protocolError(step)
        FailureType.REDIRECT_LOOP -> TelemetryErrorType.HTTP_RESPONSE_REDIRECT_LOOP
        FailureType.STREAM_RESET -> TelemetryErrorType.HTTP_PROTOCOL_ERROR
        FailureType.OTHER, null -> otherError(step)
    }

    private fun timedOut(step: ExchangeStep?): TelemetryErrorType = when (step) {
        ExchangeStep.DNS -> TelemetryErrorType.DNS_TIMED_OUT
        else -> TelemetryErrorType.TCP_TIMED_OUT
    }

    private fun closed(step: ExchangeStep?, response: ExchangeResponse?): TelemetryErrorType =
        when {
            step != ExchangeStep.RESPONSE -> TelemetryErrorType.TCP_CLOSED

            response == null -> TelemetryErrorType.HTTP_RESPONSE_INVALID_EMPTY

            response.contentLength != null ->
                TelemetryErrorType.HTTP_RESPONSE_INVALID_CONTENT_LENGTH_MISMATCH

            else -> TelemetryErrorType.HTTP_RESPONSE_INVALID_INCOMPLETE_CHUNKED_ENCODING
        }

    private fun protocolError(step: ExchangeStep?): TelemetryErrorType = when (step) {
        ExchangeStep.RESPONSE -> TelemetryErrorType.HTTP_RESPONSE_INVALID
        else -> TelemetryErrorType.HTTP_PROTOCOL_ERROR
    }

    private fun otherError(step: ExchangeStep?): TelemetryErrorType = when (step) {
        ExchangeStep.DNS -> TelemetryErrorType.DNS_FAILED
        ExchangeStep.TCP -> TelemetryErrorType.TCP_FAILED
        ExchangeStep.TLS -> TelemetryErrorType.TLS_FAILED
        ExchangeStep.REQUEST, ExchangeStep.RESPONSE -> TelemetryErrorType.HTTP_FAILED
        null -> TelemetryErrorType.UNKNOWN
    }
}
