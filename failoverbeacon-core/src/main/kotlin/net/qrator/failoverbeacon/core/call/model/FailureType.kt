package net.qrator.failoverbeacon.core.call.model

enum class FailureType {
    NAME_NOT_RESOLVED,
    TIMED_OUT,
    CLOSED,
    RESET,
    REFUSED,
    ABORTED,
    ADDRESS_INVALID,
    ADDRESS_UNREACHABLE,
    CERT_UNTRUSTED,
    CERT_NAME_MISMATCH,
    CERT_PIN_MISMATCH,
    TLS_PROTOCOL_ERROR,
    PROTOCOL_ERROR,
    REDIRECT_LOOP,
    STREAM_RESET,
    OTHER
}
