package net.qrator.failoverbeacon.core.thread

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.LogOutput

class SdkExecutorTest {
    private val previousOutput = SdkRegistry.logger.output
    private val logged = CopyOnWriteArrayList<Pair<Int, Throwable?>>()
    private val executor = SdkExecutor("test")

    @BeforeTest
    fun setUp() {
        SdkRegistry.logger.output =
            LogOutput { level, _, throwable -> logged += level to throwable }
    }

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
        SdkRegistry.logger.output = previousOutput
        SdkRegistry.clear()
    }

    @Test
    fun `runs tasks on its own daemon thread`() {
        var thread: Thread? = null

        executor.execute { thread = Thread.currentThread() }
        awaitIdle()

        assertEquals("FailoverBeacon-test", thread?.name)
        assertEquals(true, thread?.isDaemon)
    }

    @Test
    fun `shutdown lets the queued tasks finish and drops later ones`() {
        val release = CountDownLatch(1)
        val ran = CopyOnWriteArrayList<String>()
        executor.execute { release.await() }
        executor.execute { ran += "queued" }

        executor.shutdown()
        executor.execute { ran += "after shutdown" }
        release.countDown()

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf("queued"), ran)
    }

    @Test
    fun `a failing task does not stop the next ones and only the first failure is logged`() {
        val failure = IllegalStateException()
        val ran = CopyOnWriteArrayList<String>()

        executor.execute { throw failure }
        awaitIdle()
        executor.execute { throw IllegalStateException() }
        awaitIdle()
        executor.execute { ran += "next" }
        awaitIdle()

        assertEquals(listOf("next"), ran)
        val (level, throwable) = logged.single()
        assertEquals(Log.ERROR, level)
        assertSame(failure, throwable)
    }

    private fun awaitIdle() {
        val idle = CountDownLatch(1)
        executor.execute { idle.countDown() }
        assertTrue(idle.await(5, TimeUnit.SECONDS))
    }
}
