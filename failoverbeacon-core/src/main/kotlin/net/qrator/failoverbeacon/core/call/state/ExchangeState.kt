package net.qrator.failoverbeacon.core.call.state

import java.net.SocketException
import javax.net.ssl.SSLException
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.util.Interval

internal class ExchangeState(
    var serverAddress: String?,
    var serverPort: Int?,
    var method: String?,
    val resendCount: Int,
    startNanos: Long
) {
    val totalInterval = Interval(startNanos)
    val dnsInterval = Interval()
    val tlsInterval = Interval()
    val sendInterval = Interval()
    val waitInterval = Interval()
    val receiveInterval = Interval()
    val attempts = ArrayList<AttemptState>()
    val tlsStartTimes = ArrayList<Long>()
    var serverIp: String? = null
    var protocol: String? = null
    var reusedConnection = false
    var statusCode: Int? = null
    var contentLength: Long? = null
    var requestBodyBytes: Long? = null
    var responseBodyBytes: Long? = null
    var lastStep: ExchangeStep? = null
    var failure: Throwable? = null
    var canceled = false

    val responded: Boolean get() = statusCode != null
    val ended: Boolean get() = responded || failure != null

    val connectedAttempt: AttemptState?
        get() = attempts.lastOrNull { it.isConnected && it.peerAddress == serverIp }

    /** Connect attempts that owned TLS handshakes (mapped by in-flight connect timing). */
    val tlsOwners: Set<AttemptState>
        get() = tlsStartTimes.mapNotNullTo(HashSet()) { tlsStart ->
            val racers = attempts.filter { it.connectInterval.contains(tlsStart) }
            if (racers.any { it.isConnected }) {
                null
            } else {
                racers.maxByOrNull { it.connectInterval.endNanos ?: Long.MIN_VALUE }
            }
        }

    fun getConnectingAttempt(ip: String, port: Int): AttemptState? =
        attempts.firstOrNull { it.isConnecting && it.peerAddress == ip && it.port == port }

    fun classifyAttempt(attempt: AttemptState): ConnectResult {
        val owners = tlsOwners
        return when {
            attempt.isConnected -> ConnectResult.Connected
            isSuperseded(attempt, owners) -> ConnectResult.Superseded
            reachedTls(attempt, owners) -> ConnectResult.Failed(ExchangeStep.TLS, attempt.failure)
            else -> ConnectResult.Failed(ExchangeStep.TCP, attempt.failure)
        }
    }

    // OkHttp cancels racing connects once one gets through TCP by closing their sockets.
    // Cancel is a fail with a plain SocketException without subclasses.
    private fun isSuperseded(attempt: AttemptState, tlsOwners: Set<AttemptState>): Boolean {
        val failure = attempt.failure
        if (failure != null && failure.javaClass != SocketException::class.java) return false
        if (reachedTls(attempt, tlsOwners)) return false
        return attempts.any { other ->
            other !== attempt &&
                (other.isConnected || reachedTls(other, tlsOwners)) &&
                attempt.connectInterval.overlaps(other.connectInterval)
        }
    }

    private fun reachedTls(attempt: AttemptState, tlsOwners: Set<AttemptState>): Boolean =
        attempt in tlsOwners || attempt.failure is SSLException
}
