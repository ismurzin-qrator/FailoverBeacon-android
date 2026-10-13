package net.qrator.failoverbeacon.core.call.model

sealed interface ExchangeOutcome {
    data object Ok : ExchangeOutcome

    /** [step] is the last step reached, [cause] is null if no I/O error was reported. */
    data class Failed(val step: ExchangeStep?, val cause: Throwable?) : ExchangeOutcome

    /** The app canceled the call. */
    data object Canceled : ExchangeOutcome
}
