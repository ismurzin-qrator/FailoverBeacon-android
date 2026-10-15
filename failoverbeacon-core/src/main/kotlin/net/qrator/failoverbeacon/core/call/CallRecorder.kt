package net.qrator.failoverbeacon.core.call

import net.qrator.failoverbeacon.core.call.mapper.ExchangeMapper
import net.qrator.failoverbeacon.core.call.model.Exchange
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.state.AttemptState
import net.qrator.failoverbeacon.core.call.state.CallStopReason
import net.qrator.failoverbeacon.core.call.state.ConnectStopReason
import net.qrator.failoverbeacon.core.call.state.ExchangeState
import net.qrator.failoverbeacon.core.call.state.Target
import net.qrator.failoverbeacon.core.time.MonotonicClock

/** Builds the exchanges of one call from its steps and passes each finished one to [publish]. */
class CallRecorder(private val clock: MonotonicClock, private val publish: (Exchange) -> Unit) {
    private val callId = CallIdGenerator.next()
    private var callTarget: Target? = null
    private var callMethod: String? = null
    private var nextTarget: Target? = null
    private var exchangeCount = 0
    private var current: ExchangeState? = null
    private var connectionIp: String? = null
    private var connectionProtocol: String? = null
    private var finished = false

    @Synchronized
    fun callStart(serverAddress: String, serverPort: Int, method: String) {
        callTarget = Target(serverAddress, serverPort)
        callMethod = method
    }

    @Synchronized
    fun targetSelected(serverAddress: String, serverPort: Int) {
        val target = Target(serverAddress, serverPort)
        val exchange = current
        if (exchange != null && !exchange.ended) {
            if (exchange.target == null) exchange.target = target
            if (!exchange.isOtherServer(target)) return
            closeCurrent(clock.nanoTime())
        }
        nextTarget = target
    }

    @Synchronized
    fun dnsStart() {
        val now = clock.nanoTime()
        currentOrNextExchange(now)?.apply {
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
        currentOrNextExchange(now)?.apply {
            attempts += AttemptState(ip, port, startNanos = now)
            lastStep = ExchangeStep.TCP
        }
    }

    @Synchronized
    fun tlsStart() {
        current?.apply {
            tlsInterval.startNanos = clock.nanoTime()
            lastStep = ExchangeStep.TLS
        }
    }

    @Synchronized
    fun tlsEnd() {
        current?.tlsInterval?.endNanos = clock.nanoTime()
    }

    @Synchronized
    fun connectEnd(ip: String, port: Int) {
        val attempt = current?.getConnectingAttempt(ip, port, ExchangeStep.TLS)
        attempt?.connectInterval?.endNanos = clock.nanoTime()
    }

    /** [step] is TLS if the connection failed during its handshake. */
    @Synchronized
    fun connectFailed(
        ip: String,
        port: Int,
        failure: Throwable,
        step: ExchangeStep,
        stop: ConnectStopReason
    ) {
        current?.getConnectingAttempt(ip, port, step)?.apply {
            connectInterval.endNanos = clock.nanoTime()
            this.failure = failure
            failedStep = step
            this.stopReason = stop
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
        val target = Target(serverAddress, serverPort)
        val now = clock.nanoTime()
        val exchange = current
        if (exchange != null && exchange.allAttemptsFailed && exchange.isOtherServer(target)) {
            closeCurrent(now)
        }
        currentOrNextExchange(now)?.apply {
            this.target = target
            this.protocol = protocol
            serverIp = ip
            reusedConnection =
                attempts.none { it.isConnected && it.peerAddress == ip && it.port == port }
        }
        connectionIp = ip
        connectionProtocol = protocol
        nextTarget = null
    }

    @Synchronized
    fun requestHeadersStart() {
        val now = clock.nanoTime()
        currentOrNextExchange(now)?.apply {
            sendInterval.startNanos = now
            lastStep = ExchangeStep.REQUEST
            // A call reusing the connection it holds may report none for a follow-up.
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
            target = Target(serverAddress, serverPort)
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
            sendInterval.endNanos = clock.nanoTime()
            if (!responded) requestUnsent = true
            fail(ExchangeStep.REQUEST, failure)
        }
    }

    @Synchronized
    fun responseHeadersEnd(statusCode: Int, protocol: String, contentLength: Long?) {
        current?.apply {
            val now = clock.nanoTime()
            waitInterval.endNanos = now
            // The body is received from the headers on, whenever reading it starts.
            receiveInterval.startNanos = now
            lastStep = ExchangeStep.RESPONSE
            this.statusCode = statusCode
            this.protocol = protocol
            this.contentLength = contentLength
            // The server answered before the failed send ended.
            failure = null
        }
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
            if (responded) receiveInterval.endNanos = now else waitInterval.endNanos = now
            fail(ExchangeStep.RESPONSE, failure)
        }
    }

    @Synchronized
    fun callEnd() = finish(failure = null, stop = null)

    @Synchronized
    fun callFailed(failure: Throwable, stop: CallStopReason) = finish(failure, stop)

    /** Null once the call has ended: a racing connect may still be reported. */
    private fun currentOrNextExchange(now: Long): ExchangeState? {
        if (finished) return null
        val previous = current
        if (previous != null && !previous.ended) return previous
        if (previous != null) {
            previous.followedUp = true
            closeCurrent(now)
        }
        // A request that failed without a response is resent as is.
        // A follow-up's server is unknown until it is named.
        val resent = previous?.takeUnless { it.responded }
        val isFirst = exchangeCount == 0
        val next = ExchangeState(
            target = nextTarget ?: if (isFirst) callTarget else resent?.target,
            method = if (isFirst) callMethod else resent?.method,
            callId = callId,
            resendCount = exchangeCount,
            startNanos = now
        )
        nextTarget = null
        exchangeCount++
        current = next
        return next
    }

    private fun finish(failure: Throwable?, stop: CallStopReason?) {
        if (finished) return
        finished = true
        current?.apply {
            callFailure = failure
            callStopReason = stop
        }
        closeCurrent(clock.nanoTime())
    }

    // Also ends an exchange the app gave up for another server: it has no ending event.
    private fun closeCurrent(now: Long) {
        val exchange = current ?: return
        exchange.closedNanos = now
        current = null
        publish(ExchangeMapper.toExchange(exchange))
    }
}
