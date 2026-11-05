package net.qrator.failoverbeacon.failover.mapper

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.model.BodyProbeOutcome
import net.qrator.failoverbeacon.failover.model.ConnectOutcome
import net.qrator.failoverbeacon.failover.model.DnsState
import net.qrator.failoverbeacon.failover.model.HostState
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.model.RefreshTimer
import net.qrator.failoverbeacon.failover.model.SavedAddress
import net.qrator.failoverbeacon.failover.model.SavedBodyProbe
import net.qrator.failoverbeacon.failover.model.SavedHost

class SavedHostMapperTest {
    private var now = START_MILLIS
    private val timer = RefreshTimer({ now }, TimeUnit.MINUTES.toMillis(10))
    private val bodyTimer = RefreshTimer({ now }, TimeUnit.HOURS.toMillis(1))
    private val config = hostConfig(HOST, listOf(ip(9)))
    private val mapper = SavedHostMapper(listOf(config), { now }, bodyTimer)

    private fun state(vararg answer: Int): HostState =
        HostState(config).withDns(DnsState().withAnswer(answer.map(::ip), now))

    private fun address(saved: SavedHost, last: Int): SavedAddress =
        saved.addresses.single { it.address == ip(last) }

    @Test
    fun `a host with no answer and no results is not saved`() {
        assertNull(mapper.toSaved(HostState(config)))
    }

    @Test
    fun `an additional address is saved only with a result`() {
        val saved = assertNotNull(mapper.toSaved(state(1)))

        assertEquals(listOf(ip(1)), saved.addresses.map { it.address })
    }

    @Test
    fun `a failed DNS query keeps the answer and its time`() {
        val answered = state(1)

        val failed = answered.withDns(answered.dns.withFailure(now + 1))

        assertEquals(START_MILLIS, mapper.toSaved(failed)?.answeredAtMillis)
        assertEquals(listOf(ip(1)), mapper.toSaved(failed)?.addresses?.map { it.address })
    }

    @Test
    fun `a TCP or TLS failure is not saved`() {
        val saved = assertNotNull(
            mapper.toSaved(
                state(1, 2)
                    .withTcpProbe(ip(1), LayerResult.Failed)
                    .withConnectOutcome(ip(2).hostAddress!!, ConnectOutcome.TLS_FAILED)
            )
        )

        val restored = assertNotNull(mapper.toState(saved))

        assertTrue(restored.addresses.none { it.hasFailure })
    }

    @Test
    fun `a passed body with a failed layer is not saved`() {
        val probed = state(1).withBodyProbeOutcome(ip(1), BodyProbeOutcome.PASSED, now)

        val failed = probed.withBodyProbeOutcome(ip(1), BodyProbeOutcome.TLS_FAILED, now)

        assertNotNull(address(assertNotNull(mapper.toSaved(probed)), 1).bodyProbe)
        assertNull(address(assertNotNull(mapper.toSaved(failed)), 1).bodyProbe)
    }

    @Test
    fun `a probe that never read a body is not saved`() {
        val failed = state(1).withBodyProbeOutcome(ip(1), BodyProbeOutcome.TCP_FAILED, now)

        assertNull(address(assertNotNull(mapper.toSaved(failed)), 1).bodyProbe)
    }

    @Test
    fun `saving a restored state gives the same result`() {
        val saved = assertNotNull(
            mapper.toSaved(
                state(1, 2)
                    .withTcpProbe(ip(1), LayerResult.Passed(7))
                    .withBodyProbeOutcome(ip(1), BodyProbeOutcome.PASSED, now)
            )
        )

        val restored = assertNotNull(mapper.toState(saved))

        assertEquals(saved, mapper.toSaved(restored))
    }

    @Test
    fun `the saved order survives a new DNS answer`() {
        val saved = assertNotNull(
            mapper.toSaved(
                state(1, 2)
                    .withTcpProbe(ip(1), LayerResult.Passed(20))
                    .withTcpProbe(ip(2), LayerResult.Passed(10))
            )
        )
        val restored = assertNotNull(mapper.toState(saved))

        val answered = restored.withDns(DnsState().withAnswer(listOf(ip(1), ip(2)), now))

        assertEquals(listOf(ip(2), ip(1), ip(9)), answered.ips)
    }

    @Test
    fun `the probe round starts again after a restart`() {
        val saved = assertNotNull(mapper.toSaved(state(1).withTcpProbeRound(now)))

        assertTrue(assertNotNull(mapper.toState(saved)).isTcpProbeStale(timer))
    }

    @Test
    fun `an answer younger than the interval stays fresh after a restart`() {
        val saved = assertNotNull(mapper.toSaved(state(1)))
        now += TimeUnit.MINUTES.toMillis(9)

        val restored = assertNotNull(mapper.toState(saved))

        assertTrue(restored.dns.isFresh(timer))
    }

    @Test
    fun `an older answer is served but needs a new one`() {
        val saved = assertNotNull(mapper.toSaved(state(1)))
        now += TimeUnit.MINUTES.toMillis(11)

        val restored = assertNotNull(mapper.toState(saved))

        assertTrue(restored.dns.hasAnswer)
        assertTrue(restored.dns.isAnswerNeeded(timer))
    }

    @Test
    fun `an answer older than the limit or from the future drops the host`() {
        val saved = assertNotNull(mapper.toSaved(state(1)))
        val week = TimeUnit.DAYS.toMillis(7)

        now = START_MILLIS + week - 1
        assertNotNull(mapper.toState(saved))
        now = START_MILLIS + week
        assertNull(mapper.toState(saved))
        now = START_MILLIS - 1
        assertNull(mapper.toState(saved))
    }

    @Test
    fun `a host outside the config is dropped`() {
        assertNull(mapper.toState(SavedHost("other.com", START_MILLIS, emptyList())))
    }

    @Test
    fun `addresses without an answer are restored by their results`() {
        val saved = SavedHost(
            host = HOST,
            answeredAtMillis = null,
            addresses = listOf(SavedAddress(ip(9), false, 5, null))
        )

        val restored = assertNotNull(mapper.toState(saved))

        assertFalse(restored.dns.hasAnswer)
        assertEquals(LayerResult.Passed(5), restored.addresses.single().tcp)
    }

    @Test
    fun `an address removed from the config is forgotten and a new one is added`() {
        val saved = SavedHost(
            host = HOST,
            answeredAtMillis = START_MILLIS,
            addresses = listOf(
                SavedAddress(ip(1), true, null, null),
                SavedAddress(ip(8), false, 5, null)
            )
        )

        val restored = assertNotNull(mapper.toState(saved))

        assertEquals(listOf(ip(1), ip(9)), restored.ips)
    }

    @Test
    fun `a failed body keeps the address last for an hour after a restart`() {
        val saved = assertNotNull(
            mapper.toSaved(
                state(1, 2)
                    .withTcpProbe(ip(1), LayerResult.Passed(10))
                    .withTcpProbe(ip(2), LayerResult.Passed(20))
                    .withBodyProbeOutcome(ip(1), BodyProbeOutcome.BODY_FAILED, now)
            )
        )
        val hour = TimeUnit.HOURS.toMillis(1)

        now += hour - 1
        val withinHour = assertNotNull(mapper.toState(saved))
        now = START_MILLIS + hour
        val afterHour = assertNotNull(mapper.toState(saved))

        assertEquals(listOf(ip(2), ip(9), ip(1)), withinHour.ips)
        assertEquals(listOf(ip(1), ip(2), ip(9)), afterHour.ips)
        assertEquals(LayerResult.Unknown, afterHour.addresses.first().body)
    }

    @Test
    fun `a passed body saves the next probe for an hour after a restart`() {
        val saved = assertNotNull(
            mapper.toSaved(
                state(1, 2)
                    .withTcpProbe(ip(1), LayerResult.Passed(10))
                    .withTcpProbe(ip(2), LayerResult.Passed(20))
                    .withBodyProbeOutcome(ip(1), BodyProbeOutcome.PASSED, now)
            )
        )

        now += TimeUnit.MINUTES.toMillis(59)
        val restored = assertNotNull(mapper.toState(saved))
        val leader = restored.addresses.first()

        assertEquals(ip(1), leader.address)
        assertFalse(leader.isBodyProbeStale(bodyTimer))
    }

    @Test
    fun `a body result from the future is forgotten`() {
        val saved = SavedHost(
            host = HOST,
            answeredAtMillis = START_MILLIS,
            addresses = listOf(
                SavedAddress(ip(1), true, null, SavedBodyProbe(false, START_MILLIS + 1))
            )
        )

        val restored = assertNotNull(mapper.toState(saved))

        assertEquals(LayerResult.Unknown, restored.addresses.first().body)
    }

    private companion object {
        const val HOST = "a.com"
        const val START_MILLIS = 1_794_000_000_000L
    }
}
