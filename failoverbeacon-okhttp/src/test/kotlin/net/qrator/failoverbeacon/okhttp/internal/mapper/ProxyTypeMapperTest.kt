package net.qrator.failoverbeacon.okhttp.internal.mapper

import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.ProxyType

class ProxyTypeMapperTest {
    private val mapper = ProxyTypeMapper()
    private val address = InetSocketAddress.createUnresolved("proxy.test", 3128)

    @Test
    fun `maps every proxy type`() {
        assertEquals(ProxyType.DIRECT, mapper.toProxyType(Proxy.NO_PROXY))
        assertEquals(ProxyType.HTTP, mapper.toProxyType(Proxy(Proxy.Type.HTTP, address)))
        assertEquals(ProxyType.SOCKS, mapper.toProxyType(Proxy(Proxy.Type.SOCKS, address)))
    }
}
