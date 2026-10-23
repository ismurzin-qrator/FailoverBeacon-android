package net.qrator.failoverbeacon.telemetry.upload

internal class BeaconBackoff {
    private var delayPasses = 0
    private var nextAttemptPass = 0L

    fun canAttempt(pass: Long): Boolean = pass >= nextAttemptPass

    fun onFailure(pass: Long) {
        delayPasses = (delayPasses * 2).coerceIn(1, MAX_DELAY_PASSES)
        nextAttemptPass = pass + delayPasses
    }

    fun onSuccess() {
        delayPasses = 0
        nextAttemptPass = 0
    }

    private companion object {
        const val MAX_DELAY_PASSES = 60
    }
}
