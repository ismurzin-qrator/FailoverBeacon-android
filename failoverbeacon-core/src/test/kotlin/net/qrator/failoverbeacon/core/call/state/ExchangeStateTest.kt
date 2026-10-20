package net.qrator.failoverbeacon.core.call.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.call.model.ProxyType

class ExchangeStateTest {
    private val exchange =
        ExchangeState(Target("a.com", 443), "GET", callId = 0, resendCount = 0, startNanos = 0)

    @Test
    fun `connecting attempts are the unfinished connects to the address and port`() {
        val first = attempt("1.1.1.1", start = 0)
        val retry = attempt("1.1.1.1", start = 10)
        attempt("1.1.1.1", start = 20, end = 30)
        attempt("1.1.1.1", port = 8443, start = 40)

        assertEquals(listOf(first, retry), exchange.connectingAttempts("1.1.1.1", 443))
        assertTrue(exchange.connectingAttempts("2.2.2.2", 443).isEmpty())
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
