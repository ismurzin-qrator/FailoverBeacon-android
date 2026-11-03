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
}
