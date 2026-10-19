package net.qrator.failoverbeacon.core.call.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.ProxyType

class ExchangeStateTest {
    private val exchange =
        ExchangeState(Target("a.com", 443), "GET", callId = 0, resendCount = 0, startNanos = 0)

    @Test
    fun `a TCP failure goes to the earliest connect to an address, a handshake to the latest`() {
        val first = attempt("1.1.1.1", start = 0)
        val retry = attempt("1.1.1.1", start = 10)
        attempt("1.1.1.1", start = 20, end = 30)
        attempt("1.1.1.1", port = 8443, start = 40)

        assertSame(first, exchange.getConnectingAttempt("1.1.1.1", 443, ExchangeStep.TCP))
        assertSame(retry, exchange.getConnectingAttempt("1.1.1.1", 443, ExchangeStep.TLS))
        assertNull(exchange.getConnectingAttempt("2.2.2.2", 443, ExchangeStep.TCP))
    }

    @Test
    fun `tells another server by address and port`() {
        assertFalse(exchange.isOtherServer(Target("a.com", 443)))
        assertTrue(exchange.isOtherServer(Target("a.com", 8443)))
        assertTrue(exchange.isOtherServer(Target("b.com", 443)))
        val unnamed = ExchangeState(null, null, callId = 0, resendCount = 1, startNanos = 0)
        assertFalse(unnamed.isOtherServer(Target("b.com", 443)))
    }

    private fun attempt(ip: String, port: Int = 443, start: Long, end: Long? = null) =
        AttemptState(ip, port, ProxyType.DIRECT, start).also {
            it.connectInterval.endNanos = end
            exchange.attempts += it
        }
}
