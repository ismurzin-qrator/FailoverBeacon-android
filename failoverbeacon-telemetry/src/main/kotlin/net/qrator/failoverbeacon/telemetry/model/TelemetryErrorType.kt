package net.qrator.failoverbeacon.telemetry.model

/**
 * Values from the NEL dictionary of network error types and Chromium's extensions, each with its
 * [code] and the one [phase] NEL gives it (§6).
 */
internal enum class TelemetryErrorType(val code: String, val phase: TelemetryPhase) {
    OK("ok", TelemetryPhase.APPLICATION),
    HTTP_ERROR("http.error", TelemetryPhase.APPLICATION),
    ABANDONED("abandoned", TelemetryPhase.APPLICATION),
    UNKNOWN("unknown", TelemetryPhase.APPLICATION),
    DNS_NAME_NOT_RESOLVED("dns.name_not_resolved", TelemetryPhase.DNS),
    DNS_FAILED("dns.failed", TelemetryPhase.DNS),
    DNS_TIMED_OUT("dns.timed_out", TelemetryPhase.DNS),
    TCP_TIMED_OUT("tcp.timed_out", TelemetryPhase.CONNECTION),
    TCP_CLOSED("tcp.closed", TelemetryPhase.CONNECTION),
    TCP_RESET("tcp.reset", TelemetryPhase.CONNECTION),
    TCP_REFUSED("tcp.refused", TelemetryPhase.CONNECTION),
    TCP_ABORTED("tcp.aborted", TelemetryPhase.CONNECTION),
    TCP_ADDRESS_INVALID("tcp.address_invalid", TelemetryPhase.CONNECTION),
    TCP_ADDRESS_UNREACHABLE("tcp.address_unreachable", TelemetryPhase.CONNECTION),
    TCP_FAILED("tcp.failed", TelemetryPhase.CONNECTION),
    TLS_CERT_AUTHORITY_INVALID("tls.cert.authority_invalid", TelemetryPhase.CONNECTION),
    TLS_CERT_NAME_INVALID("tls.cert.name_invalid", TelemetryPhase.CONNECTION),
    TLS_CERT_PINNED_KEY_NOT_IN_CERT_CHAIN(
        "tls.cert.pinned_key_not_in_cert_chain",
        TelemetryPhase.CONNECTION
    ),
    TLS_PROTOCOL_ERROR("tls.protocol.error", TelemetryPhase.CONNECTION),
    TLS_FAILED("tls.failed", TelemetryPhase.CONNECTION),
    HTTP_PROTOCOL_ERROR("http.protocol.error", TelemetryPhase.APPLICATION),
    HTTP_RESPONSE_INVALID("http.response.invalid", TelemetryPhase.APPLICATION),
    HTTP_RESPONSE_INVALID_EMPTY("http.response.invalid.empty", TelemetryPhase.APPLICATION),
    HTTP_RESPONSE_INVALID_CONTENT_LENGTH_MISMATCH(
        "http.response.invalid.content_length_mismatch",
        TelemetryPhase.APPLICATION
    ),
    HTTP_RESPONSE_INVALID_INCOMPLETE_CHUNKED_ENCODING(
        "http.response.invalid.incomplete_chunked_encoding",
        TelemetryPhase.APPLICATION
    ),
    HTTP_RESPONSE_REDIRECT_LOOP("http.response.redirect_loop", TelemetryPhase.APPLICATION),
    HTTP_FAILED("http.failed", TelemetryPhase.APPLICATION)
}
