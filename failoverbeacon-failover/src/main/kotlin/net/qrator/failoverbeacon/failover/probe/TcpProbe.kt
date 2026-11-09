package net.qrator.failoverbeacon.failover.probe

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import net.qrator.failoverbeacon.core.time.MonotonicClock

internal class TcpProbe(private val clock: MonotonicClock, private val timeoutMillis: Int) :
    Closeable {
    private val sockets = OpenSockets()

    fun run(address: InetAddress, port: Int): Long? {
        val socket = sockets.open() ?: return null
        try {
            val start = clock.nanoTime()
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            return clock.nanoTime() - start
        } catch (_: IOException) {
            return null
        } finally {
            sockets.release(socket)
        }
    }

    fun abort() = sockets.abortAll()

    override fun close() = sockets.close()
}
