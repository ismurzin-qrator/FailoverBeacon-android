package net.qrator.failoverbeacon.core.call.state

import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.util.Interval

/** What happened during one exchange, as reported. */
internal class ExchangeState(
    var target: Target?,
    var method: String?,
    val callId: Long,
    val resendCount: Int,
    val startNanos: Long
) {
    val dnsInterval = Interval()
    val tlsInterval = Interval()
    val sendInterval = Interval()
    val waitInterval = Interval()
    val receiveInterval = Interval()
    val attempts = ArrayList<AttemptState>()
    var serverIp: String? = null
    var protocol: String? = null
    var reusedConnection = false
    var statusCode: Int? = null
    var contentLength: Long? = null
    var requestBodyBytes: Long? = null
    var responseBodyBytes: Long? = null
    var lastStep: ExchangeStep? = null
    var failure: Throwable? = null
    var requestUnsent = false
    var followedUp = false
    var callFailure: Throwable? = null
    var callStopReason: CallStopReason? = null
    var closedNanos: Long? = null

    val responded: Boolean get() = statusCode != null
    val ended: Boolean get() = responded || failure != null
    val completed: Boolean get() = responded && failure == null

    /** Why the call stopped this exchange; a call stopped after a complete response did not. */
    val stopReason: CallStopReason? get() = callStopReason.takeUnless { completed }
    val allAttemptsFailed: Boolean
        get() = attempts.isNotEmpty() && attempts.all { it.failure != null }

    fun isOtherServer(target: Target): Boolean = this.target != null && this.target != target

    fun fail(step: ExchangeStep, failure: Throwable) {
        // Keeps the first failure, later ones are its consequences.
        if (this.failure != null) return
        lastStep = step
        this.failure = failure
    }

    fun getConnectingAttempt(ip: String, port: Int, step: ExchangeStep): AttemptState? {
        val connecting = attempts.filter {
            it.isConnecting && it.peerAddress == ip &&
                it.port == port
        }
        return if (step == ExchangeStep.TLS) connecting.lastOrNull() else connecting.firstOrNull()
    }
}
