package net.qrator.failoverbeacon.core.thread

import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue

class SdkThreads : Closeable {
    val storage = SdkExecutor(
        name = "storage",
        coreThreads = 1,
        maxThreads = 1,
        keepAliveMillis = 0,
        queue = LinkedBlockingQueue()
    )
    val upload = SdkScheduledExecutor("upload")
    val failoverResolve = idlePool("failover")
    val failoverProbe = idlePool("failover-probe")

    override fun close() {
        // Queued storage work, such as deleting events after consent is withdrawn, must finish.
        storage.shutdown()
        upload.shutdownNow()
        failoverResolve.shutdownNow()
        failoverProbe.shutdownNow()
    }

    private fun idlePool(name: String) = SdkExecutor(
        name = name,
        coreThreads = FAILOVER_THREADS,
        maxThreads = FAILOVER_THREADS,
        keepAliveMillis = IDLE_MILLIS,
        queue = LinkedBlockingQueue()
    ).apply { allowCoreThreadTimeOut(true) }

    private companion object {
        const val FAILOVER_THREADS = 4
        const val IDLE_MILLIS = 30_000L
    }
}
