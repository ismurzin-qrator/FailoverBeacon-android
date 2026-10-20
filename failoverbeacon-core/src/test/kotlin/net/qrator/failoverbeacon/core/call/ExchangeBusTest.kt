package net.qrator.failoverbeacon.core.call

import android.util.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.core.logging.InternalLogger
import net.qrator.failoverbeacon.core.logging.LogOutput

class ExchangeBusTest {
    private val logged = mutableListOf<Pair<Int, Throwable?>>()
    private val logger = InternalLogger().apply {
        output = LogOutput { level, _, throwable -> logged += level to throwable }
    }
    private val bus = ExchangeBus(logger)
    private val exchange = Exchange(
        callId = 0,
        request = ExchangeRequest("a.com", 443, "GET", bodyBytes = null),
        response = null,
        resendCount = 0,
        connection = null,
        reusedConnection = false,
        dnsAddresses = null,
        attempts = emptyList(),
        timings = ExchangeTimings(null, null, null, null, null, null),
        durationNanos = 0,
        outcome = ExchangeOutcome.Canceled(step = null)
    )

    @Test
    fun `has subscribers only after one subscribes`() {
        assertFalse(bus.hasSubscribers)

        bus.subscribe {}

        assertTrue(bus.hasSubscribers)
    }

    @Test
    fun `delivers each exchange to every subscriber in order`() {
        val received = mutableListOf<String>()
        bus.subscribe { received += "first" }
        bus.subscribe { received += "second" }

        bus.publish(exchange)

        assertEquals(listOf("first", "second"), received)
    }

    @Test
    fun `passes the published exchange`() {
        val received = mutableListOf<Exchange>()
        bus.subscribe(received::add)

        bus.publish(exchange)

        assertSame(exchange, received.single())
    }

    @Test
    fun `a failing subscriber does not stop the others`() {
        val received = mutableListOf<String>()
        bus.subscribe { throw IllegalStateException() }
        bus.subscribe { throw NoSuchMethodError() }
        bus.subscribe { received += "last" }

        bus.publish(exchange)

        assertEquals(listOf("last"), received)
    }

    @Test
    fun `logs only the first subscriber failure`() {
        val failure = IllegalStateException()
        bus.subscribe { throw failure }

        bus.publish(exchange)
        bus.publish(exchange)

        val (level, throwable) = logged.single()
        assertEquals(Log.ERROR, level)
        assertSame(failure, throwable)
    }

    @Test
    fun `lets other errors through`() {
        bus.subscribe { throw AssertionError() }

        assertFailsWith<AssertionError> { bus.publish(exchange) }
    }

    @Test
    fun `a subscriber added during delivery gets the next exchange`() {
        val received = mutableListOf<String>()
        bus.subscribe {
            received += "first"
            if (received.size == 1) bus.subscribe { received += "added" }
        }

        bus.publish(exchange)
        bus.publish(exchange)

        assertEquals(listOf("first", "first", "added"), received)
    }
}
