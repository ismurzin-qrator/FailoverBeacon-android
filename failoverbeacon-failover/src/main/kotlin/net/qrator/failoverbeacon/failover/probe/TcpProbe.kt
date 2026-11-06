package net.qrator.failoverbeacon.failover.probe

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import net.qrator.failoverbeacon.core.time.MonotonicClock
import net.qrator.failoverbeacon.failover.model.LayerResult

internal class TcpProbe(private val clock: MonotonicClock, private val timeoutMillis: Int) :
    Closeable {
    private val sockets = OpenSockets()

    fun run(address: InetAddress, port: Int): LayerResult {
        val socket = sockets.open() ?: return LayerResult.Failed
        try {
            val start = clock.nanoTime()
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            return LayerResult.Passed(clock.nanoTime() - start)
        } catch (_: IOException) {
            return LayerResult.Failed
        } finally {
            sockets.release(socket)
        }
    }

    fun abort() = sockets.abortAll()

    override fun close() = sockets.close()
}
