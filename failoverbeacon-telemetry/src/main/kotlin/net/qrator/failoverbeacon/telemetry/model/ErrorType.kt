package net.qrator.failoverbeacon.telemetry.model

/**
 * Values from the NEL dictionary of network error types and Chromium's extensions, each with its
 * [code] and the one [phase] NEL gives it (§6).
 */
internal enum class ErrorType(val code: String, val phase: Phase) {
    OK("ok", Phase.APPLICATION),
    HTTP_ERROR("http.error", Phase.APPLICATION),
    ABANDONED("abandoned", Phase.APPLICATION),
    UNKNOWN("unknown", Phase.APPLICATION),
    DNS_NAME_NOT_RESOLVED("dns.name_not_resolved", Phase.DNS),
    DNS_FAILED("dns.failed", Phase.DNS),
    DNS_TIMED_OUT("dns.timed_out", Phase.DNS),
    TCP_TIMED_OUT("tcp.timed_out", Phase.CONNECTION),
    TCP_CLOSED("tcp.closed", Phase.CONNECTION),
    TCP_RESET("tcp.reset", Phase.CONNECTION),
    TCP_REFUSED("tcp.refused", Phase.CONNECTION),
    TCP_ABORTED("tcp.aborted", Phase.CONNECTION),
    TCP_ADDRESS_INVALID("tcp.address_invalid", Phase.CONNECTION),
    TCP_ADDRESS_UNREACHABLE("tcp.address_unreachable", Phase.CONNECTION),
    TCP_FAILED("tcp.failed", Phase.CONNECTION),
    TLS_CERT_AUTHORITY_INVALID("tls.cert.authority_invalid", Phase.CONNECTION),
    TLS_CERT_NAME_INVALID("tls.cert.name_invalid", Phase.CONNECTION),
    TLS_CERT_PINNED_KEY_NOT_IN_CERT_CHAIN(
        "tls.cert.pinned_key_not_in_cert_chain",
        Phase.CONNECTION
    ),
    TLS_PROTOCOL_ERROR("tls.protocol.error", Phase.CONNECTION),
    TLS_FAILED("tls.failed", Phase.CONNECTION),
    HTTP_PROTOCOL_ERROR("http.protocol.error", Phase.APPLICATION),
    HTTP_RESPONSE_INVALID("http.response.invalid", Phase.APPLICATION),
    HTTP_RESPONSE_INVALID_EMPTY("http.response.invalid.empty", Phase.APPLICATION),
    HTTP_RESPONSE_INVALID_CONTENT_LENGTH_MISMATCH(
        "http.response.invalid.content_length_mismatch",
        Phase.APPLICATION
    ),
    HTTP_RESPONSE_INVALID_INCOMPLETE_CHUNKED_ENCODING(
        "http.response.invalid.incomplete_chunked_encoding",
        Phase.APPLICATION
    ),
    HTTP_RESPONSE_REDIRECT_LOOP("http.response.redirect_loop", Phase.APPLICATION),
    HTTP_FAILED("http.failed", Phase.APPLICATION)
}
