package net.qrator.failoverbeacon.core.thread

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.LogOutput

class SdkScheduledExecutorTest {
    private val previousOutput = SdkRegistry.logger.output
    private val logged = CopyOnWriteArrayList<Int>()
    private val executor = SdkScheduledExecutor("test")

    @BeforeTest
    fun setUp() {
        SdkRegistry.logger.output = LogOutput { level, _, _ -> logged += level }
    }

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
        SdkRegistry.logger.output = previousOutput
        SdkRegistry.clear()
    }

    @Test
    fun `runs scheduled tasks on its own daemon thread`() {
        val ran = CountDownLatch(1)
        var thread: Thread? = null

        executor.scheduleWithFixedDelay({
            thread = Thread.currentThread()
            ran.countDown()
        }, 0, 1, TimeUnit.HOURS)

        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertEquals("FailoverBeacon-test", thread?.name)
        assertEquals(true, thread?.isDaemon)
    }

    @Test
    fun `a failing task keeps its schedule and only the first failure is logged`() {
        val runs = CountDownLatch(3)

        executor.scheduleWithFixedDelay({
            runs.countDown()
            throw IllegalStateException()
        }, 0, 1, TimeUnit.MILLISECONDS)

        assertTrue(runs.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(Log.ERROR), logged.toList())
    }

    @Test
    fun `a task scheduled after shutdown is dropped`() {
        executor.shutdown()

        executor.scheduleWithFixedDelay({}, 0, 1, TimeUnit.MILLISECONDS)

        assertTrue(executor.queue.isEmpty())
    }
}
