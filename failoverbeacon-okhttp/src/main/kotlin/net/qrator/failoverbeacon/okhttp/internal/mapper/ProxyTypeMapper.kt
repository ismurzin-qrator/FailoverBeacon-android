package net.qrator.failoverbeacon.okhttp.internal.mapper

import java.net.Proxy
import net.qrator.failoverbeacon.core.call.model.ProxyType

internal class ProxyTypeMapper {
    fun toProxyType(proxy: Proxy): ProxyType = when (proxy.type()) {
        Proxy.Type.DIRECT -> ProxyType.DIRECT
        Proxy.Type.HTTP -> ProxyType.HTTP
        Proxy.Type.SOCKS -> ProxyType.SOCKS
    }
}
