package net.qrator.failoverbeacon.core.call.state

enum class CallStopReason {
    FAILED,
    CANCELED,
    TIMED_OUT,
    RESPONSE_REJECTED
}
