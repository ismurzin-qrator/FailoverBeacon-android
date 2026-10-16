package net.qrator.failoverbeacon.core.call.model

sealed interface ConnectResult {
    data object Connected : ConnectResult

    /** [step] is TCP or TLS, [type] is null if no failure was reported. */
    data class Failed(val step: ExchangeStep, val type: FailureType?) : ConnectResult

    /** Another connection was ready first. */
    data object Superseded : ConnectResult

    /** The app canceled the call while connecting. */
    data object Canceled : ConnectResult
}
