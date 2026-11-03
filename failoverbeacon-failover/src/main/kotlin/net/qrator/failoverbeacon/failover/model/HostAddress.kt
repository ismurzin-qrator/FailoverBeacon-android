package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

internal data class HostAddress(
    val address: InetAddress,
    val tcp: LayerResult = LayerResult.Unknown,
    val tls: LayerResult = LayerResult.Unknown
) {
    val hasFailure: Boolean get() = tcp == LayerResult.Failed || tls == LayerResult.Failed

    fun withConnectOutcome(outcome: ConnectOutcome): HostAddress = when (outcome) {
        ConnectOutcome.CONNECTED_PLAIN -> withTcpPassed()

        ConnectOutcome.CONNECTED_SECURE -> withTcpPassed().copy(
            tls = LayerResult.Passed(nanos = null)
        )

        ConnectOutcome.TCP_FAILED -> copy(tcp = LayerResult.Failed)

        ConnectOutcome.TLS_FAILED -> withTcpPassed().copy(tls = LayerResult.Failed)
    }

    private fun withTcpPassed(): HostAddress =
        if (tcp is LayerResult.Passed) this else copy(tcp = LayerResult.Passed(nanos = null))
}
