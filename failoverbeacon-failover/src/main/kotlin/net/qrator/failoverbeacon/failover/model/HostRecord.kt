package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

internal class HostRecord private constructor(
    val host: String,
    val resolvedAddresses: List<InetAddress>,
    private val resolvedAtNanos: Long?,
    val additionalAddresses: List<InetAddress>
) {
    constructor(host: String, additionalAddresses: List<InetAddress>) :
        this(host, emptyList(), null, additionalAddresses)

    val addresses: List<InetAddress> = (resolvedAddresses + additionalAddresses).distinct()

    fun isFresh(nowNanos: Long, maxAgeNanos: Long): Boolean =
        resolvedAtNanos != null && nowNanos - resolvedAtNanos <= maxAgeNanos

    fun withResolved(addresses: List<InetAddress>, nowNanos: Long): HostRecord =
        if (addresses.isEmpty()) {
            this
        } else {
            HostRecord(
                host,
                addresses,
                nowNanos,
                additionalAddresses
            )
        }
}
