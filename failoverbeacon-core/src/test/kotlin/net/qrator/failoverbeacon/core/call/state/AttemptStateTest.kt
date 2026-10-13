package net.qrator.failoverbeacon.core.call.state

import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttemptStateTest {
    @Test
    fun `is connecting until it ends`() {
        val attempt = AttemptState("1.1.1.1", 443, startNanos = 0)

        assertTrue(attempt.isConnecting)
        assertFalse(attempt.isConnected)
    }

    @Test
    fun `is connected when it ends without a failure`() {
        val attempt = AttemptState("1.1.1.1", 443, startNanos = 0)

        attempt.connectInterval.endNanos = 10

        assertFalse(attempt.isConnecting)
        assertTrue(attempt.isConnected)
    }

    @Test
    fun `is neither connecting nor connected when it ends with a failure`() {
        val attempt = AttemptState("1.1.1.1", 443, startNanos = 0)

        attempt.connectInterval.endNanos = 10
        attempt.failure = ConnectException()

        assertFalse(attempt.isConnecting)
        assertFalse(attempt.isConnected)
    }
}
