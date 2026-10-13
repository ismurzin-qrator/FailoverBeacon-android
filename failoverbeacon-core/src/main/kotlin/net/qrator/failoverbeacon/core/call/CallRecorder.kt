package net.qrator.failoverbeacon.core.call

import java.net.ProtocolException
import net.qrator.failoverbeacon.core.call.mapper.ExchangeMapper
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.state.AttemptState
import net.qrator.failoverbeacon.core.call.state.ExchangeState
import net.qrator.failoverbeacon.core.time.MonotonicClock

/** Builds the exchanges of one call from its steps and passes each finished one to [publish]. */
class CallRecorder(private val clock: MonotonicClock, private val publish: (Exchange) -> Unit) {
    private var callServerAddress = ""
    private var callServerPort = 0
    private var callMethod = ""
    private var exchangeCount = 0
    private var current: ExchangeState? = null
    private var connectionIp: String? = null
    private var connectionProtocol: String? = null
    private var finished = false

    @Synchronized
    fun callStart(serverAddress: String, serverPort: Int, method: String) {
        callServerAddress = serverAddress
        callServerPort = serverPort
        callMethod = method
    }

    @Synchronized
    fun dnsStart() {
        val now = clock.nanoTime()
        currentOrNextExchange(now).apply {
            dnsInterval.startNanos = now
            lastStep = ExchangeStep.DNS
        }
    }

    @Synchronized
    fun dnsEnd() {
        current?.dnsInterval?.endNanos = clock.nanoTime()
    }

    @Synchronized
    fun connectStart(ip: String, port: Int) {
        val now = clock.nanoTime()
        currentOrNextExchange(now).apply {
            attempts += AttemptState(ip, port, startNanos = now)
            lastStep = ExchangeStep.TCP
        }
    }

    @Synchronized
    fun tlsStart() {
        current?.apply {
            val now = clock.nanoTime()
            tlsInterval.startNanos = now
            tlsStartTimes += now
            lastStep = ExchangeStep.TLS
        }
    }

    @Synchronized
    fun tlsEnd() {
        current?.tlsInterval?.endNanos = clock.nanoTime()
    }

    @Synchronized
    fun connectEnd(ip: String, port: Int) {
        current?.getConnectingAttempt(ip, port)?.connectInterval?.endNanos = clock.nanoTime()
    }

    @Synchronized
    fun connectFailed(ip: String, port: Int, failure: Throwable) {
        current?.getConnectingAttempt(ip, port)?.apply {
            connectInterval.endNanos = clock.nanoTime()
            this.failure = failure
        }
    }

    @Synchronized
    fun connectionAcquired(
        ip: String,
        port: Int,
        serverAddress: String,
        serverPort: Int,
        protocol: String
    ) {
        currentOrNextExchange(clock.nanoTime()).apply {
            this.serverAddress = serverAddress
            this.serverPort = serverPort
            this.protocol = protocol
            serverIp = ip
            reusedConnection =
                attempts.none { it.isConnected && it.peerAddress == ip && it.port == port }
        }
        connectionIp = ip
        connectionProtocol = protocol
    }

    @Synchronized
    fun requestHeadersStart() {
        val now = clock.nanoTime()
        currentOrNextExchange(now).apply {
            sendInterval.startNanos = now
            lastStep = ExchangeStep.REQUEST
            // OkHttp reports no connectionAcquired when the call reuses the connection it holds.
            if (serverIp == null) {
                serverIp = connectionIp
                protocol = connectionProtocol
                reusedConnection = connectionIp != null
            }
        }
    }

    @Synchronized
    fun requestHeadersEnd(serverAddress: String, serverPort: Int, method: String) {
        current?.apply {
            val now = clock.nanoTime()
            this.serverAddress = serverAddress
            this.serverPort = serverPort
            this.method = method
            sendInterval.endNanos = now
            waitInterval.startNanos = now
        }
    }

    @Synchronized
    fun requestBodyEnd(byteCount: Long) {
        current?.apply {
            val now = clock.nanoTime()
            requestBodyBytes = byteCount
            sendInterval.endNanos = now
            waitInterval.startNanos = now
        }
    }

    @Synchronized
    fun requestFailed(failure: Throwable) {
        current?.apply {
            lastStep = ExchangeStep.REQUEST
            this.failure = failure
        }
    }

    @Synchronized
    fun responseHeadersEnd(statusCode: Int, protocol: String, contentLength: Long?) {
        current?.apply {
            waitInterval.endNanos = clock.nanoTime()
            lastStep = ExchangeStep.RESPONSE
            this.statusCode = statusCode
            this.protocol = protocol
            this.contentLength = contentLength
            // OkHttp 4+ still reads a response after sending the request body failed.
            failure = null
        }
    }

    @Synchronized
    fun responseBodyStart() {
        current?.receiveInterval?.startNanos = clock.nanoTime()
    }

    @Synchronized
    fun responseBodyEnd(byteCount: Long) {
        current?.apply {
            receiveInterval.endNanos = clock.nanoTime()
            responseBodyBytes = byteCount
        }
    }

    @Synchronized
    fun responseFailed(failure: Throwable) {
        current?.apply {
            val now = clock.nanoTime()
            // The failure came while waiting for the response headers.
            if (receiveInterval.startNanos == null) waitInterval.endNanos = now
            receiveInterval.endNanos = now
            lastStep = ExchangeStep.RESPONSE
            this.failure = failure
        }
    }

    @Synchronized
    fun callEnd() = finish(failure = null, canceled = false, timedOut = false)

    /** [canceled] if the app canceled the call, a call timeout is a failure. */
    @Synchronized
    fun callFailed(failure: Throwable, canceled: Boolean, timedOut: Boolean) =
        finish(failure, canceled, timedOut)

    private fun currentOrNextExchange(now: Long): ExchangeState {
        val exchange = current
        if (exchange != null && !exchange.ended) return exchange
        if (exchange != null) {
            exchange.totalInterval.endNanos = now
            publish(ExchangeMapper.toExchange(exchange))
        }
        val next = if (exchangeCount == 0) {
            ExchangeState(callServerAddress, callServerPort, callMethod, 0, startNanos = now)
        } else {
            // A follow-up's target is unknown until OkHttp names it.
            ExchangeState(null, null, null, resendCount = exchangeCount, startNanos = now)
        }
        exchangeCount++
        current = next
        return next
    }

    private fun finish(failure: Throwable?, canceled: Boolean, timedOut: Boolean) {
        if (finished) return
        finished = true
        val last = current ?: return
        last.totalInterval.endNanos = last.receiveInterval.endNanos ?: clock.nanoTime()
        last.failure = when {
            timedOut -> failure

            last.failure != null -> last.failure

            // A failure after the response and before the next request is not this exchange's,
            // except a redirect loop.
            last.responded && failure !is ProtocolException -> null

            else -> failure
        }
        last.canceled = canceled
        publish(ExchangeMapper.toExchange(last))
    }
}
