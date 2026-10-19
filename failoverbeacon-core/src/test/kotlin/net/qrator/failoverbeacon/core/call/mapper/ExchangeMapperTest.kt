package net.qrator.failoverbeacon.core.call.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ConnectAttempt
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.ExchangeConnection
import net.qrator.failoverbeacon.core.call.model.ExchangeOutcome
import net.qrator.failoverbeacon.core.call.model.ExchangeRequest
import net.qrator.failoverbeacon.core.call.model.ExchangeResponse
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.ExchangeTimings
import net.qrator.failoverbeacon.core.call.model.ExchangeTls
import net.qrator.failoverbeacon.core.call.model.ProxyType
import net.qrator.failoverbeacon.core.call.state.AttemptState
import net.qrator.failoverbeacon.core.call.state.ExchangeState
import net.qrator.failoverbeacon.core.call.state.Target

class ExchangeMapperTest {
    private val state =
        ExchangeState(Target("a.com", 443), "POST", callId = 7, resendCount = 1, startNanos = 0)

    @Test
    fun `maps every field of a finished exchange`() {
        state.apply {
            attempts += AttemptState("1.1.1.1", 443, ProxyType.DIRECT, startNanos = 10).also {
                it.connectInterval.endNanos = 30
            }
            dnsAddresses = listOf("1.1.1.1", "1.1.1.2")
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
            connection = CONNECTION
            statusCode = 200
            contentLength = 5
            requestBodyBytes = 3
            responseBodyBytes = 5
            lastStep = ExchangeStep.RESPONSE
        }

        val exchange = ExchangeMapper(TestFailureMapper).toExchange(state)

        assertEquals(7L, exchange.callId)
        assertEquals(ExchangeRequest("a.com", 443, "POST", bodyBytes = 3), exchange.request)
        assertEquals(ExchangeResponse(200, contentLength = 5, bodyBytes = 5), exchange.response)
        assertEquals(1, exchange.resendCount)
        assertEquals(CONNECTION, exchange.connection)
        assertEquals(false, exchange.reusedConnection)
        assertEquals(listOf("1.1.1.1", "1.1.1.2"), exchange.dnsAddresses)
        assertEquals(
            listOf(ConnectAttempt("1.1.1.1", ProxyType.DIRECT, 20, ConnectResult.Connected)),
            exchange.attempts
        )
        assertEquals(ExchangeTimings(5, 20, 13, 4, 15, 9), exchange.timings)
        assertEquals(60, exchange.durationNanos)
        assertEquals(ExchangeOutcome.Ok, exchange.outcome)
    }

    @Test
    fun `the connect time is the one of the attempt to the server IP`() {
        state.attempts += AttemptState("1.1.1.1", 443, ProxyType.DIRECT, startNanos = 0).also {
            it.connectInterval.endNanos = 10
        }
        state.attempts += AttemptState("1.1.1.2", 443, ProxyType.DIRECT, startNanos = 20).also {
            it.connectInterval.endNanos = 35
        }
        state.connection = CONNECTION.copy(peerAddress = "1.1.1.2")

        assertEquals(15L, ExchangeMapper(TestFailureMapper).toExchange(state).timings.connectNanos)
    }

    private companion object {
        val CONNECTION = ExchangeConnection(
            peerAddress = "1.1.1.1",
            protocol = "h2",
            proxy = ProxyType.DIRECT,
            tls = ExchangeTls("TLSv1.3", "TLS_AES_128_GCM_SHA256", "CN=Test CA")
        )
    }
}
