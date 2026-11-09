package net.qrator.failoverbeacon.okhttp.fakes

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class Relay(targetPort: Int) : Closeable {
    private val listener = ServerSocket(0, 50, LOOPBACK)
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val connections = AtomicInteger()

    @Volatile
    var isBlackHole = false

    val port: Int get() = listener.localPort
    val connectionCount: Int get() = connections.get()

    init {
        thread(isDaemon = true) {
            while (!listener.isClosed) {
                val downstream = runCatching { listener.accept() }.getOrNull() ?: break
                val upstream = Socket(LOOPBACK, targetPort)
                sockets += listOf(downstream, upstream)
                connections.incrementAndGet()
                pump(downstream, upstream)
                pump(upstream, downstream)
            }
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach(Socket::close)
    }

    private fun pump(from: Socket, to: Socket) = thread(isDaemon = true) {
        val buffer = ByteArray(8192)
        runCatching {
            while (true) {
                val count = from.getInputStream().read(buffer)
                if (count < 0) break
                if (!isBlackHole) to.getOutputStream().write(buffer, 0, count)
            }
        }
        from.close()
        to.close()
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
