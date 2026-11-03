package net.qrator.failoverbeacon.failover.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.qrator.failoverbeacon.core.call.model.ConnectResult
import net.qrator.failoverbeacon.core.call.model.ExchangeStep
import net.qrator.failoverbeacon.core.call.model.FailureType
import net.qrator.failoverbeacon.failover.model.ConnectOutcome

class ConnectOutcomeMapperTest {
    private val mapper = ConnectOutcomeMapper()

    @Test
    fun `a connection is secure when the request is`() {
        assertEquals(
            ConnectOutcome.CONNECTED_SECURE,
            mapper.toOutcome(ConnectResult.Connected, isSecure = true)
        )
        assertEquals(
            ConnectOutcome.CONNECTED_PLAIN,
            mapper.toOutcome(ConnectResult.Connected, isSecure = false)
        )
    }

    @Test
    fun `a failure belongs to the layer of its step`() {
        assertEquals(
            ConnectOutcome.TCP_FAILED,
            mapper.toOutcome(failed(ExchangeStep.TCP), isSecure = true)
        )
        assertEquals(
            ConnectOutcome.TLS_FAILED,
            mapper.toOutcome(failed(ExchangeStep.TLS), isSecure = true)
        )
    }

    @Test
    fun `a lost race, a cancel and a failure without a reason have no outcome`() {
        assertNull(mapper.toOutcome(ConnectResult.Superseded, isSecure = true))
        assertNull(mapper.toOutcome(ConnectResult.Canceled, isSecure = true))
        assertNull(
            mapper.toOutcome(ConnectResult.Failed(ExchangeStep.TLS, type = null), isSecure = true)
        )
    }

    private fun failed(step: ExchangeStep) = ConnectResult.Failed(step, FailureType.TIMED_OUT)
}
