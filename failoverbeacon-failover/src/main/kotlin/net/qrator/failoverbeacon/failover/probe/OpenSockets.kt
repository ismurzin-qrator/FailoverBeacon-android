package net.qrator.failoverbeacon.failover.probe

import android.net.TrafficStats
import java.io.Closeable
import java.net.Socket

internal class OpenSockets : Closeable {
    private val lock = Any()
    private val sockets = HashSet<Socket>()
    private var isClosed = false

    fun open(): Socket? {
        TrafficStats.setThreadStatsTag(TRAFFIC_STATS_TAG)
        val socket = Socket()
        synchronized(lock) {
            if (isClosed) return null
            sockets += socket
        }
        return socket
    }

    fun release(socket: Socket) {
        synchronized(lock) { sockets -= socket }
        runCatching(socket::close)
    }

    override fun close() {
        val open = synchronized(lock) {
            isClosed = true
            sockets.toList()
        }
        open.forEach { runCatching(it::close) }
    }

    private companion object {
        const val TRAFFIC_STATS_TAG = 0x4650 // FP
    }
}
