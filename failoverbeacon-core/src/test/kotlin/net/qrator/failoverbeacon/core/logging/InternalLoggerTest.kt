package net.qrator.failoverbeacon.core.logging

import android.util.Log
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

    private data class Message(val level: Int, val message: String, val throwable: Throwable?)
}
