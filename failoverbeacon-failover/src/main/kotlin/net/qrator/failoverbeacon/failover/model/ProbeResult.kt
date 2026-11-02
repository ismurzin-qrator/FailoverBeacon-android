package net.qrator.failoverbeacon.failover.model

internal sealed interface ProbeResult {
    data class Connected(val connectNanos: Long) : ProbeResult

    data object Unknown : ProbeResult

    data object Failed : ProbeResult
}
