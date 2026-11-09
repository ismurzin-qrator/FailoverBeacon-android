package net.qrator.failoverbeacon.failover.probe

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import net.qrator.failoverbeacon.failover.fakes.ManualExecutor
import net.qrator.failoverbeacon.failover.fakes.hostConfig
import net.qrator.failoverbeacon.failover.fakes.ip
import net.qrator.failoverbeacon.failover.fakes.ips
import net.qrator.failoverbeacon.failover.fakes.network
import net.qrator.failoverbeacon.failover.model.BodyProbeConfig
import net.qrator.failoverbeacon.failover.model.BodyProbeOutcome
import net.qrator.failoverbeacon.failover.model.LayerResult
import net.qrator.failoverbeacon.failover.model.MaxAge
import net.qrator.failoverbeacon.failover.network.KnownNetworks
import net.qrator.failoverbeacon.failover.network.NetworkSession
import net.qrator.failoverbeacon.failover.network.NetworkSessions

class BodyProberTest {
    private val executor = ManualExecutor()
    private var now = 0L
    private val maxAge = MaxAge({ now }, INTERVAL)
    private val outcomes = mutableMapOf<InetAddress, BodyProbeOutcome>()
    private val runs = mutableListOf<Pair<InetAddress, String>>()
    private var onRun: () -> Unit = {}
    private val sessions = NetworkSessions(
        KnownNetworks(
            listOf(
                hostConfig(API, bodyProbe = BodyProbeConfig("/probe.bin", 1_024)),
                hostConfig(NO_BODY_PROBE)
            ),
            maxNetworks = 8
        ),
        maxAge
    ).apply { switchTo(network(1)) }
    private val prober = BodyProber(sessions, ::run, maxAge, executor)

    private val session get() = assertNotNull(sessions.current)

    @Test
    fun `a host without a body probe is not probed`() {
        session.probed(NO_BODY_PROBE)

        prober.probeStaleHosts()

        assertEquals(0, executor.queued)
    }

    @Test
    fun `a passed leader ends the round and is not probed again`() {
        session.probed(API)
        outcomes[ip(1)] = BodyProbeOutcome.PASSED

        prober.probeIfStale(API)
        executor.runAll()
        prober.probeIfStale(API)

        assertEquals(listOf(ip(1) to API), runs)
        assertEquals(LayerResult.Passed(nanos = null), session[API]?.addresses?.first()?.body)
        assertEquals(0, executor.queued)
    }

    @Test
    fun `a failed leader goes last and the next address is probed in the same round`() {
        session.probed(API)
        outcomes[ip(1)] = BodyProbeOutcome.BODY_FAILED
        outcomes[ip(2)] = BodyProbeOutcome.PASSED

        prober.probeIfStale(API)
        executor.runAll()

        assertEquals(listOf(ip(1), ip(2)), runs.map { it.first })
        assertEquals(listOf(ip(2), ip(1)), session[API]?.ips)
    }

    @Test
    fun `a host in a round is not queued again and a later round runs after it finishes`() {
        session.probed(API)
        outcomes[ip(1)] = BodyProbeOutcome.PASSED

        prober.probeIfStale(API)
        prober.probeIfStale(API)
        val whileRunning = executor.queued
        executor.runAll()
        now += INTERVAL
        prober.probeIfStale(API)
        executor.runAll()

        assertEquals(1, whileRunning)
        assertEquals(listOf(ip(1), ip(1)), runs.map { it.first })
    }

    @Test
    fun `a network change ends the round and drops its result`() {
        session.probed(API)
        val first = session
        onRun = { sessions.switchTo(network(2, key = "CELLULAR")) }

        prober.probeIfStale(API)
        executor.runAll()

        assertEquals(1, runs.size)
        assertEquals(LayerResult.Unknown, first[API]?.addresses?.first()?.tls)
    }

    private fun run(
        address: InetAddress,
        host: String,
        port: Int,
        settings: BodyProbeConfig
    ): BodyProbeOutcome {
        runs += address to host
        onRun()
        return outcomes[address] ?: BodyProbeOutcome.TCP_FAILED
    }

    private fun NetworkSession.probed(host: String) {
        saveAnswer(host, listOf(ip(1), ip(2)))
        saveTcpProbe(host, ip(1), LayerResult.Passed(nanos = 10))
        saveTcpProbe(host, ip(2), LayerResult.Passed(nanos = 20))
    }

    private companion object {
        const val API = "api.example"
        const val NO_BODY_PROBE = "other.example"
        const val INTERVAL = 100L
    }
}
