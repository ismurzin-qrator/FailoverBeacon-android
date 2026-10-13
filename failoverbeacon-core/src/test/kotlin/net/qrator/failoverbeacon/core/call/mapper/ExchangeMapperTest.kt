package net.qrator.failoverbeacon.core.call.mapper

import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.qrator.failoverbeacon.core.call.model.ConnectAttempt
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.core.call.state.AttemptState
import net.qrator.failoverbeacon.core.call.state.ExchangeState

class ExchangeMapperTest {
    @Test
    fun `maps every field of a finished exchange`() {
        val state = ExchangeState("a.com", 443, "POST", resendCount = 1, startNanos = 0).apply {
            attempts += AttemptState("1.1.1.1", 443, startNanos = 10).also {
                it.connectInterval.endNanos = 30
            }
            dnsInterval.startNanos = 2
            dnsInterval.endNanos = 7
            tlsInterval.startNanos = 15
            tlsInterval.endNanos = 28
            sendInterval.startNanos = 31
            sendInterval.endNanos = 35
            waitInterval.startNanos = 35
            waitInterval.endNanos = 50
            receiveInterval.startNanos = 51
            receiveInterval.endNanos = 60
            totalInterval.endNanos = 60
            serverIp = "1.1.1.1"
            protocol = "h2"
            statusCode = 200
            contentLength = 5
            requestBodyBytes = 3
            responseBodyBytes = 5
            lastStep = ExchangeStep.RESPONSE
        }

        val exchange = ExchangeMapper.toExchange(state)

        assertEquals(ExchangeRequest("a.com", 443, "POST", bodyBytes = 3), exchange.request)
        assertEquals(ExchangeResponse(200, contentLength = 5, bodyBytes = 5), exchange.response)
        assertEquals(1, exchange.resendCount)
        assertEquals("1.1.1.1", exchange.serverIp)
        assertEquals("h2", exchange.protocol)
        assertEquals(false, exchange.reusedConnection)
        assertEquals(
            listOf(ConnectAttempt("1.1.1.1", connectNanos = 20, result = ConnectResult.Connected)),
            exchange.attempts
        )
        assertEquals(ExchangeTimings(5, 20, 13, 4, 15, 9), exchange.timings)
        assertEquals(60, exchange.durationNanos)
        assertEquals(ExchangeOutcome.Ok, exchange.outcome)
    }

    @Test
    fun `has no response without a status code`() {
        assertNull(ExchangeMapper.toExchange(exchange()).response)
    }

    @Test
    fun `cancel wins over a failure`() {
        val state = exchange().apply {
            failure = IOException("Canceled")
            canceled = true
        }

        assertEquals(ExchangeOutcome.Canceled, ExchangeMapper.toExchange(state).outcome)
    }

    @Test
    fun `a failure wins over a response`() {
        val cut = SocketTimeoutException("read")
        val state = exchange().apply {
            statusCode = 200
            lastStep = ExchangeStep.RESPONSE
            failure = cut
        }

        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.RESPONSE, cut),
            ExchangeMapper.toExchange(state).outcome
        )
    }

    @Test
    fun `an exchange that ended with neither a response nor a failure failed without a cause`() {
        val state = exchange().apply { lastStep = ExchangeStep.TCP }

        assertEquals(
            ExchangeOutcome.Failed(ExchangeStep.TCP, cause = null),
            ExchangeMapper.toExchange(state).outcome
        )
    }

    @Test
    fun `a redirect loop failure keeps the redirect status`() {
        val loop = ProtocolException("Too many follow-up requests: 21")
        val state = exchange().apply {
            statusCode = 302
            lastStep = ExchangeStep.RESPONSE
            failure = loop
        }

        val exchange = ExchangeMapper.toExchange(state)

        assertEquals(302, exchange.response?.statusCode)
        assertEquals(ExchangeOutcome.Failed(ExchangeStep.RESPONSE, loop), exchange.outcome)
    }

    @Test
    fun `a pooled connection has no connect time`() {
        val state = exchange().apply {
            serverIp = "1.1.1.1"
            reusedConnection = true
        }

        val exchange = ExchangeMapper.toExchange(state)

        assertEquals(true, exchange.reusedConnection)
        assertNull(exchange.timings.connectNanos)
    }

    @Test
    fun `an unfinished duration is zero`() {
        assertEquals(0, ExchangeMapper.toExchange(exchange()).durationNanos)
    }

    private fun exchange() = ExchangeState(null, null, null, resendCount = 1, startNanos = 0)
}
