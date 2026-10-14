package net.qrator.failoverbeacon.core.call.model

sealed interface ConnectResult {
    data object Connected : ConnectResult

    /** [step] is TCP or TLS, [cause] is null if none was reported. */
    data class Failed(val step: ExchangeStep, val cause: Throwable?) : ConnectResult

    /** Another connection was ready first. */
    data object Superseded : ConnectResult

    /** The app canceled the call while connecting. */
    data object Canceled : ConnectResult
}
