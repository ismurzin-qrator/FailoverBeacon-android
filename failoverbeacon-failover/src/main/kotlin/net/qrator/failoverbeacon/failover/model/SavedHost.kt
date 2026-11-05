package net.qrator.failoverbeacon.failover.model

internal data class SavedHost(
    val host: String,
    val answeredAtMillis: Long?,
    val addresses: List<SavedAddress>
)
