package net.qrator.failoverbeacon.failover.model

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.failover.fakes.ip

class HostAddressTest {
    private val probed = LayerResult.Passed(nanos = 40)
    private val passed = LayerResult.Passed(nanos = null)

    @Test
    fun `a failed handshake passes TCP and fails TLS`() {
        val unprobed = HostAddress(ip(1)).withConnectOutcome(ConnectOutcome.TLS_FAILED)
        val timed = HostAddress(ip(1), probed).withConnectOutcome(ConnectOutcome.TLS_FAILED)

        assertEquals(HostAddress(ip(1), passed, LayerResult.Failed), unprobed)
        assertEquals(HostAddress(ip(1), probed, LayerResult.Failed), timed)
    }

    @Test
    fun `a failed TCP connect fails TCP and leaves TLS`() {
        val failed = HostAddress(ip(1), probed, passed)
            .withConnectOutcome(ConnectOutcome.TCP_FAILED)

        assertEquals(HostAddress(ip(1), LayerResult.Failed, passed), failed)
    }

    @Test
    fun `a secure connection clears the failures and keeps the probe time`() {
        val timed = HostAddress(ip(1), probed, LayerResult.Failed)
            .withConnectOutcome(ConnectOutcome.CONNECTED_SECURE)
        val failed = HostAddress(ip(1), LayerResult.Failed, LayerResult.Failed)
            .withConnectOutcome(ConnectOutcome.CONNECTED_SECURE)

        assertEquals(HostAddress(ip(1), probed, passed), timed)
        assertEquals(HostAddress(ip(1), passed, passed), failed)
    }

    @Test
    fun `a plain connection clears TCP and leaves TLS`() {
        val connected = HostAddress(ip(1), LayerResult.Failed, LayerResult.Failed)
            .withConnectOutcome(ConnectOutcome.CONNECTED_PLAIN)

        assertEquals(HostAddress(ip(1), passed, LayerResult.Failed), connected)
    }

    @Test
    fun `a body probe writes the layers it reached and its time`() {
        val address = HostAddress(ip(1), probed)

        fun after(outcome: BodyProbeOutcome) = address.withBodyProbeOutcome(outcome, atNanos = 5)

        assertEquals(
            HostAddress(ip(1), probed, passed, passed, bodyProbedAtNanos = 5),
            after(BodyProbeOutcome.PASSED)
        )
        assertEquals(
            HostAddress(ip(1), LayerResult.Failed, bodyProbedAtNanos = 5),
            after(BodyProbeOutcome.TCP_FAILED)
        )
        assertEquals(
            HostAddress(ip(1), probed, LayerResult.Failed, bodyProbedAtNanos = 5),
            after(BodyProbeOutcome.TLS_FAILED)
        )
        assertEquals(
            HostAddress(ip(1), probed, passed, LayerResult.Failed, bodyProbedAtNanos = 5),
            after(BodyProbeOutcome.BODY_FAILED)
        )
    }

    @Test
    fun `a real connection does not clear a body failure`() {
        val connected = HostAddress(ip(1), probed, passed, LayerResult.Failed)
            .withConnectOutcome(ConnectOutcome.CONNECTED_SECURE)

        assertEquals(LayerResult.Failed, connected.body)
    }
}
