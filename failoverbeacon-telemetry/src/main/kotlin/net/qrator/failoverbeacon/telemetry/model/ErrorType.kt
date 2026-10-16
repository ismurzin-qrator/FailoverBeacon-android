package net.qrator.failoverbeacon.telemetry.model

/** Values and [code]s from the NEL dictionary of network error types. */
internal enum class ErrorType(val code: String) {
    OK("ok"),
    HTTP_ERROR("http.error"),
    ABANDONED("abandoned"),
    UNKNOWN("unknown"),
    DNS_NAME_NOT_RESOLVED("dns.name_not_resolved"),
    DNS_FAILED("dns.failed"),
    TCP_TIMED_OUT("tcp.timed_out"),
    TCP_CLOSED("tcp.closed"),
    TCP_RESET("tcp.reset"),
    TCP_REFUSED("tcp.refused"),
    TCP_ABORTED("tcp.aborted"),
    TCP_ADDRESS_INVALID("tcp.address_invalid"),
    TCP_ADDRESS_UNREACHABLE("tcp.address_unreachable"),
    TCP_FAILED("tcp.failed"),
    TLS_CERT_AUTHORITY_INVALID("tls.cert.authority_invalid"),
    TLS_CERT_NAME_INVALID("tls.cert.name_invalid"),
    TLS_CERT_PINNED_KEY_NOT_IN_CERT_CHAIN("tls.cert.pinned_key_not_in_cert_chain"),
    TLS_PROTOCOL_ERROR("tls.protocol.error"),
    TLS_FAILED("tls.failed"),
    HTTP_PROTOCOL_ERROR("http.protocol.error"),
    HTTP_RESPONSE_INVALID("http.response.invalid"),
    HTTP_FAILED("http.failed")
}
