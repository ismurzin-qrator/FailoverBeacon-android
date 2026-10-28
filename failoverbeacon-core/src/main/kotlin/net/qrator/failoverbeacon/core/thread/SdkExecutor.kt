package net.qrator.failoverbeacon.core.thread

import java.util.concurrent.BlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.util.catchSdkFailure

class SdkExecutor(
    name: String,
    coreThreads: Int,
    maxThreads: Int,
    keepAliveMillis: Long,
    queue: BlockingQueue<Runnable>
) : ThreadPoolExecutor(
    coreThreads,
    maxThreads,
    keepAliveMillis,
    TimeUnit.MILLISECONDS,
    queue,
    { task -> Thread(task, "FailoverBeacon-$name").apply { isDaemon = true } },
    { _, executor -> if (!executor.isShutdown) throw RejectedExecutionException() }
) {
    override fun execute(command: Runnable) {
        super.execute { catchSdkFailure(::logFailure) { command.run() } }
    }

    private fun logFailure(failure: Throwable) {
        SdkRegistry.logger.error(failure, onlyOnce = true) {
            "[Core] SDK task failed. Further failures are not logged"
        }
    }
}
