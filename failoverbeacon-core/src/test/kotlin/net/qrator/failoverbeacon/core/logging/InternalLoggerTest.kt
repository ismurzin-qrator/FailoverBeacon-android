package net.qrator.failoverbeacon.core.logging

import android.util.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InternalLoggerTest {
    private val messages = mutableListOf<Message>()
    private val logger = InternalLogger().apply {
        output = LogOutput { level, message, throwable ->
            messages += Message(level, message, throwable)
        }
    }

    @Test
    fun `passes messages from WARN up by default, with their throwable`() {
        val error = IllegalStateException()

        logger.info { "info" }
        logger.log(Log.DEBUG, "debug")
        logger.warn { "warn" }
        logger.error(error) { "error" }

        assertEquals(
            listOf(Message(Log.WARN, "warn", null), Message(Log.ERROR, "error", error)),
            messages
        )
    }

    @Test
    fun `does not build a message nobody receives`() {
        var built = false

        logger.debug {
            built = true
            "debug"
        }
        InternalLogger().error {
            built = true
            "error"
        }

        assertFalse(built)
    }

    @Test
    fun `ignores exceptions thrown by the output`() {
        logger.output = LogOutput { _, _, _ -> throw RuntimeException() }

        logger.error { "error" }
    }

    @Test
    fun `writes an onlyOnce message once, after it is first received`() {
        logger.minLevel = Log.ASSERT
        logger.error(onlyOnce = true) { "failed" }
        logger.minLevel = Log.WARN
        logger.error(onlyOnce = true) { "failed" }
        logger.error(onlyOnce = true) { "failed" }

        assertEquals(listOf("failed"), messages.map { it.message })
    }

    @Test
    fun `rejects levels outside VERBOSE to ASSERT`() {
        assertFailsWith<IllegalArgumentException> { logger.minLevel = Log.VERBOSE - 1 }
        assertFailsWith<IllegalArgumentException> { logger.minLevel = Log.ASSERT + 1 }
        assertTrue(messages.isEmpty())
    }

    private data class Message(val level: Int, val message: String, val throwable: Throwable?)
}
