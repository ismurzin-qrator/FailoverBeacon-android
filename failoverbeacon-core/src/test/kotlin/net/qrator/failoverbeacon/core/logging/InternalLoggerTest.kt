package net.qrator.failoverbeacon.core.logging

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InternalLoggerTest {
    private val messages = mutableListOf<Message>()
    private val logger = InternalLogger().apply {
        output = LogOutput { level, message, throwable ->
            messages += Message(level, message, throwable)
        }
    }

    @Test
    fun `default level is WARN`() {
        assertEquals(Log.WARN, InternalLogger().minLevel)
    }

    @Test
    fun `drops messages below the level`() {
        logger.info { "info" }
        logger.debug { "debug" }

        assertTrue(messages.isEmpty())
    }

    @Test
    fun `passes level, message, and throwable at and above the level`() {
        val error = IllegalStateException()

        logger.warn { "warn" }
        logger.error(error) { "error" }

        assertEquals(
            listOf(Message(Log.WARN, "warn", null), Message(Log.ERROR, "error", error)),
            messages
        )
    }

    @Test
    fun `log filters by level when called directly`() {
        logger.log(Log.DEBUG, "debug")

        assertTrue(messages.isEmpty())
    }

    @Test
    fun `does not build the message when the level is disabled`() {
        var built = false

        logger.debug {
            built = true
            "debug"
        }

        assertFalse(built)
    }

    @Test
    fun `logs nothing without an output`() {
        val logger = InternalLogger()

        assertFalse(logger.isLoggable(Log.ERROR))
        logger.error { "error" }
    }

    @Test
    fun `ignores exceptions thrown by the output`() {
        logger.output = LogOutput { _, _, _ -> throw IllegalStateException() }

        logger.error { "error" }
    }

    @Test
    fun `accepts every Log level`() {
        for (level in Log.VERBOSE..Log.ASSERT) {
            logger.minLevel = level
            assertEquals(level, logger.minLevel)
        }
    }

    @Test
    fun `rejects levels outside VERBOSE to ASSERT`() {
        for (level in listOf(Log.VERBOSE - 1, Log.ASSERT + 1)) {
            assertFailsWith<IllegalArgumentException>("$level") { logger.minLevel = level }
        }
        assertEquals(Log.WARN, logger.minLevel)
    }

    @Test
    fun `passes the same throwable instance`() {
        val error = RuntimeException()

        logger.warn(error) { "warn" }

        assertSame(error, messages.single().throwable)
    }

    @Test
    fun `logs an onlyOnce message only the first time`() {
        logger.error(onlyOnce = true) { "failed" }
        logger.error(onlyOnce = true) { "failed" }

        assertEquals(listOf(Message(Log.ERROR, "failed", null)), messages)
    }

    @Test
    fun `tracks each onlyOnce message separately`() {
        logger.error(onlyOnce = true) { "first" }
        logger.error(onlyOnce = true) { "second" }

        assertEquals(listOf("first", "second"), messages.map { it.message })
    }

    @Test
    fun `logs a message without onlyOnce every time`() {
        logger.error { "failed" }
        logger.error { "failed" }

        assertEquals(2, messages.size)
    }

    @Test
    fun `an onlyOnce message dropped by the level is logged once enabled`() {
        logger.minLevel = Log.ASSERT
        logger.error(onlyOnce = true) { "failed" }
        logger.minLevel = Log.WARN
        logger.error(onlyOnce = true) { "failed" }

        assertEquals(listOf("failed"), messages.map { it.message })
    }

    @Test
    fun `an onlyOnce message dropped without an output is logged once there is one`() {
        val logged = mutableListOf<String>()
        val logger = InternalLogger()

        logger.log(Log.ERROR, "failed", onlyOnce = true)
        logger.output = LogOutput { _, message, _ -> logged += message }
        logger.log(Log.ERROR, "failed", onlyOnce = true)

        assertEquals(listOf("failed"), logged)
    }

    @Test
    fun `logs an onlyOnce message again once forgotten`() {
        logger.error(onlyOnce = true) { "failed" }
        logger.forgetLoggedOnce()
        logger.error(onlyOnce = true) { "failed" }

        assertEquals(listOf("failed", "failed"), messages.map { it.message })
    }

    @Test
    fun `logs an onlyOnce message once across threads`() {
        val logged = CopyOnWriteArrayList<String>()
        val logger = InternalLogger().apply {
            output = LogOutput { _, message, _ -> logged += message }
        }
        val start = CountDownLatch(1)
        val threads = List(8) {
            thread {
                start.await()
                repeat(100) { logger.error(onlyOnce = true) { "failed" } }
            }
        }

        start.countDown()
        threads.forEach { it.join() }

        assertEquals(listOf("failed"), logged)
    }

    private data class Message(val level: Int, val message: String, val throwable: Throwable?)
}
