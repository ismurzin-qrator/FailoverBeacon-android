package net.qrator.failoverbeacon.failover.probe

import android.net.TrafficStats
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import net.qrator.failoverbeacon.core.time.MonotonicClock
import net.qrator.failoverbeacon.failover.model.ProbeResult

internal class TcpProbe(private val clock: MonotonicClock, private val timeoutMillis: Int) :
    Closeable {
    private val lock = Any()
    private val openSockets = HashSet<Socket>()
    private var isClosed = false

    fun connect(address: InetAddress, port: Int): ProbeResult {
        TrafficStats.setThreadStatsTag(TRAFFIC_STATS_TAG)
        val socket = Socket()
        try {
            synchronized(lock) {
                if (isClosed) return ProbeResult.Failed
                openSockets += socket
            }
            val start = clock.nanoTime()
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            return ProbeResult.Connected(clock.nanoTime() - start)
        } catch (_: IOException) {
            return ProbeResult.Failed
        } finally {
            synchronized(lock) { openSockets -= socket }
            runCatching(socket::close)
        }
    }

    override fun close() {
        val sockets = synchronized(lock) {
            isClosed = true
            openSockets.toList()
        }
        sockets.forEach { runCatching(it::close) }
    }

    private companion object {
        const val TRAFFIC_STATS_TAG = 0x4650 // FP
    }
}
