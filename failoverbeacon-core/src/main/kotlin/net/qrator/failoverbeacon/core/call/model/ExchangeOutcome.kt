package net.qrator.failoverbeacon.core.call.model

sealed interface ExchangeOutcome {
    data object Ok : ExchangeOutcome

    /** [step] is the step that failed, [type] is null if no failure was reported. */
    data class Failed(val step: ExchangeStep?, val type: FailureType?) : ExchangeOutcome

    /** The app canceled the call. */
    data object Canceled : ExchangeOutcome
}
