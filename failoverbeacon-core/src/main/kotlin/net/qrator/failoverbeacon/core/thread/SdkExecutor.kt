package net.qrator.failoverbeacon.core.thread

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.util.catchSdkFailure

class SdkExecutor(name: String, capacity: Int, onDropped: () -> Unit) :
    ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity),
        { task -> Thread(task, "FailoverBeacon-$name").apply { isDaemon = true } },
        { _, executor -> if (!executor.isShutdown) onDropped() }
    ) {
    override fun execute(command: Runnable) {
        super.execute { catchSdkFailure(::logFailure) { command.run() } }
    }

    private fun logFailure(failure: Throwable) {
        SdkRegistry.logger.error(failure, onlyOnce = true) {
            "SDK task failed. Further failures are not logged"
        }
    }
}
