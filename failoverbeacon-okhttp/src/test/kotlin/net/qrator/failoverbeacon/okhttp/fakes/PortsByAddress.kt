package net.qrator.failoverbeacon.okhttp.fakes

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

class PortsByAddress(private val ports: Map<InetAddress, Int>) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val port = ports.getValue((endpoint as InetSocketAddress).address)
            super.connect(InetSocketAddress(LOOPBACK, port), timeout)
        }
    }

    override fun createSocket(host: String, port: Int): Socket =
        throw UnsupportedOperationException()

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = throw UnsupportedOperationException()

    override fun createSocket(host: InetAddress, port: Int): Socket =
        throw UnsupportedOperationException()

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = throw UnsupportedOperationException()

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
