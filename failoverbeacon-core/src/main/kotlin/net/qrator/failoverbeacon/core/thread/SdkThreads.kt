package net.qrator.failoverbeacon.core.thread

import java.io.Closeable

class SdkThreads : Closeable {
    val serial = SdkExecutor("serial", threads = 1, keepAliveMillis = 0)
    val scheduler = SdkScheduledExecutor("scheduler")
    val dns = SdkExecutor("dns", POOL_THREADS, IDLE_MILLIS).apply { allowCoreThreadTimeOut(true) }
    val probes = SdkExecutor("probes", POOL_THREADS, IDLE_MILLIS).apply {
        allowCoreThreadTimeOut(true)
    }

    override fun close() {
        serial.shutdown()
        scheduler.shutdownNow()
        dns.shutdownNow()
        probes.shutdownNow()
    }

    private companion object {
        const val POOL_THREADS = 4
        const val IDLE_MILLIS = 30_000L
    }
}
