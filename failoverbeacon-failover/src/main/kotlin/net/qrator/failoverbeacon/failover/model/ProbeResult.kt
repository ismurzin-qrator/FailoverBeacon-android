package net.qrator.failoverbeacon.failover.model

internal sealed interface ProbeResult {
    data class Connected(val connectNanos: Long) : ProbeResult

    data object Unknown : ProbeResult

    data object Failed : ProbeResult

    companion object {
        val fastestFirst: Comparator<ProbeResult> = compareBy(
            { result ->
                when (result) {
                    is Connected -> 0
                    Unknown -> 1
                    Failed -> 2
                }
            },
            { result -> (result as? Connected)?.connectNanos }
        )
    }
}
