package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress

internal data class HostAddress(
    val address: InetAddress,
    val tcp: LayerResult = LayerResult.Unknown,
    val tls: LayerResult = LayerResult.Unknown,
    val body: LayerResult = LayerResult.Unknown,
    val bodyProbedAtMillis: Long? = null
) {
    val hasFailure: Boolean
        get() = tcp == LayerResult.Failed || tls == LayerResult.Failed || body == LayerResult.Failed

    fun isBodyProbeStale(maxAge: MaxAge): Boolean =
        bodyProbedAtMillis == null || maxAge.isOver(bodyProbedAtMillis)

    fun withConnectOutcome(outcome: ConnectOutcome): HostAddress = when (outcome) {
        ConnectOutcome.CONNECTED_PLAIN -> withTcpPassed()

        ConnectOutcome.CONNECTED_SECURE -> withTcpPassed().copy(
            tls = LayerResult.Passed(nanos = null)
        )

        ConnectOutcome.TCP_FAILED -> copy(tcp = LayerResult.Failed)

        ConnectOutcome.TLS_FAILED -> withTcpPassed().copy(tls = LayerResult.Failed)
    }

    fun withBodyProbeOutcome(outcome: BodyProbeOutcome, atMillis: Long): HostAddress {
        val passed = LayerResult.Passed(nanos = null)
        val updated = when (outcome) {
            BodyProbeOutcome.PASSED -> withTcpPassed().copy(tls = passed, body = passed)

            BodyProbeOutcome.TCP_FAILED -> copy(tcp = LayerResult.Failed)

            BodyProbeOutcome.TLS_FAILED -> withTcpPassed().copy(tls = LayerResult.Failed)

            BodyProbeOutcome.BODY_FAILED -> withTcpPassed().copy(
                tls = passed,
                body = LayerResult.Failed
            )
        }
        return updated.copy(bodyProbedAtMillis = atMillis)
    }

    fun withResponse(isAnswered: Boolean, atMillis: Long): HostAddress = copy(
        body = if (isAnswered) LayerResult.Passed(nanos = null) else LayerResult.Failed,
        bodyProbedAtMillis = atMillis
    )

    private fun withTcpPassed(): HostAddress =
        if (tcp is LayerResult.Passed) this else copy(tcp = LayerResult.Passed(nanos = null))
}
