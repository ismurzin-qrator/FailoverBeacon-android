package net.qrator.failoverbeacon.core.thread

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.util.catchSdkFailure

class SdkExecutor(name: String) :
    ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { task -> Thread(task, "FailoverBeacon-$name").apply { isDaemon = true } },
        DiscardPolicy()
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
