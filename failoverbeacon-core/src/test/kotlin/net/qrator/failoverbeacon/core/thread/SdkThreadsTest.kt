package net.qrator.failoverbeacon.core.thread

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SdkThreadsTest {
    private val threads = SdkThreads()

    @AfterTest
    fun tearDown() = threads.close()

    @Test
    fun `close stops every pool and lets queued serial work finish`() {
        val release = CountDownLatch(1)
        val isQueuedWorkDone = AtomicBoolean()
        threads.serial.execute { release.await() }
        threads.serial.execute { isQueuedWorkDone.set(true) }

        threads.close()
        release.countDown()

        assertTrue(threads.scheduler.isShutdown)
        assertTrue(threads.dns.isShutdown)
        assertTrue(threads.probes.isShutdown)
        assertTrue(threads.serial.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(isQueuedWorkDone.get())
    }
}
