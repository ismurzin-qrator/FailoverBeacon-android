package net.qrator.failoverbeacon.core.call

import android.util.Log
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot

class ExchangeBusTest {
    private val logged = mutableListOf<Pair<Int, Throwable?>>()
    private val previousOutput = SdkRegistry.logger.output
    private val bus = ExchangeBus()
    private val exchange = Exchange(
        callId = 0,
        request = ExchangeRequest("https", "a.com", 443, "GET", bodyBytes = null),
        response = null,
        resendCount = 0,
        connection = null,
        reusedConnection = false,
        dnsAddresses = null,
        attempts = emptyList(),
        timings = ExchangeTimings(null, null, null, null, null, null),
        durationNanos = 0,
        outcome = ExchangeOutcome.Canceled(step = null),
        network = NetworkSnapshot.Unknown
    )

    @BeforeTest
    fun setUp() {
        SdkRegistry.logger.output =
            LogOutput { level, _, throwable -> logged += level to throwable }
    }

    @AfterTest
    fun tearDown() {
        SdkRegistry.logger.output = previousOutput
        SdkRegistry.clear()
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
}
