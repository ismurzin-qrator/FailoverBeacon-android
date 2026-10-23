package net.qrator.failoverbeacon.core.thread

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.util.catchSdkFailure

class SdkScheduledExecutor(name: String) :
    ScheduledThreadPoolExecutor(
        1,
        { task -> Thread(task, "FailoverBeacon-$name").apply { isDaemon = true } },
        DiscardPolicy()
    ) {
    override fun scheduleWithFixedDelay(
        command: Runnable,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> = super.scheduleWithFixedDelay(
        { catchSdkFailure(::logFailure) { command.run() } },
        initialDelay,
        delay,
        unit
    )

    private fun logFailure(failure: Throwable) {
        SdkRegistry.logger.error(failure, onlyOnce = true) {
            "SDK task failed. Further failures are not logged"
        }
    }
}
