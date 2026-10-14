package net.qrator.failoverbeacon.core.call.state

import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.util.Interval

internal class AttemptState(val peerAddress: String, val port: Int, startNanos: Long) {
    val connectInterval = Interval(startNanos)
    var failure: Throwable? = null
    var failedStep: ExchangeStep? = null
    var stopReason: ConnectStopReason? = null

    val isConnecting: Boolean get() = connectInterval.endNanos == null
    val isConnected: Boolean get() = connectInterval.endNanos != null && failure == null
}
