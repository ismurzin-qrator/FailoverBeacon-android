package net.qrator.failoverbeacon.telemetry

internal enum class DropReason(val code: String) {
    MEMORY_OVERFLOW("memory_overflow"),
    DISK_OVERFLOW("disk_overflow"),
    EXPIRED("expired"),
    SEND_ERROR("send_error")
}
