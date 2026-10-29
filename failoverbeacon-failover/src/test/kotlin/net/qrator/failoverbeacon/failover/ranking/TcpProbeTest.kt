package net.qrator.failoverbeacon.failover.ranking

import android.net.TrafficStats
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import net.qrator.failoverbeacon.failover.model.ProbeResult
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TcpProbeTest {
    private val server = ServerSocket(0, 1, LOOPBACK).apply { soTimeout = 1_000 }
    private var now = 0L
    private val probe = TcpProbe(clock = { now.also { now += 40 } }, timeoutMillis = 1_000)

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `a connect is timed with the clock and its socket is closed`() {
        val result = probe.connect(LOOPBACK, server.localPort)

        assertEquals(ProbeResult.Connected(connectNanos = 40), result)
        server.accept().use { assertEquals(-1, it.getInputStream().read()) }
    }

    @Test
    fun `a refused connect fails`() {
        val port = server.localPort
        server.close()

        assertEquals(ProbeResult.Failed, probe.connect(LOOPBACK, port))
    }

    @Test
    fun `probe sockets carry the SDK traffic tag`() {
        probe.connect(LOOPBACK, server.localPort)

        assertEquals(0x4650, TrafficStats.getThreadStatsTag())
    }

    @Test
    fun `after close nothing is connected`() {
        probe.close()

        assertEquals(ProbeResult.Failed, probe.connect(LOOPBACK, server.localPort))
        assertFailsWith<SocketTimeoutException> { server.accept() }
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
