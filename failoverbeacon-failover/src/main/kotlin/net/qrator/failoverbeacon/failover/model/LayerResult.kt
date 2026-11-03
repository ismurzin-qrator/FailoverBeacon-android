package net.qrator.failoverbeacon.failover.model

internal sealed interface LayerResult {
    data object Unknown : LayerResult

    data object Failed : LayerResult

    data class Passed(val nanos: Long?) : LayerResult
}
