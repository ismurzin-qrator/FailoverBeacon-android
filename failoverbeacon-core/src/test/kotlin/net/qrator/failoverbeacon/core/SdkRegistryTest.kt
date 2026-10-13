package net.qrator.failoverbeacon.core

import android.content.ContextWrapper
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.call.ExchangeBus
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.core.logging.error

class SdkRegistryTest {
    private val previousOutput = SdkRegistry.logger.output

    @AfterTest
    fun tearDown() {
        SdkRegistry.logger.output = previousOutput
        SdkRegistry.clear()
    }

    @Test
    fun `installs one instance`() {
        val first = instance()

        assertTrue(SdkRegistry.install(first))
        assertFalse(SdkRegistry.install(instance()))
        assertSame(first, SdkRegistry.instance)
    }

    @Test
    fun `clear forgets the instance and the messages logged once`() {
        val logged = mutableListOf<String>()
        SdkRegistry.logger.output = LogOutput { _, message, _ -> logged += message }
        SdkRegistry.install(instance())
        SdkRegistry.logger.error(onlyOnce = true) { "failed" }

        SdkRegistry.clear()
        SdkRegistry.logger.error(onlyOnce = true) { "failed" }

        assertNull(SdkRegistry.instance)
        assertEquals(listOf("failed", "failed"), logged)
    }

    private fun instance() = SdkInstance(
        appContext = ContextWrapper(null),
        consent = TrackingConsent.GRANTED,
        clock = { 0 },
        exchangeBus = ExchangeBus(SdkRegistry.logger)
    )
}
