package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.call.model.ProxyType as ExchangeProxyType
import net.qrator.failoverbeacon.telemetry.model.ProxyType

internal class ProxyTypeMapper {
    fun toProxyType(proxy: ExchangeProxyType): ProxyType = when (proxy) {
        ExchangeProxyType.DIRECT -> ProxyType.DIRECT
        ExchangeProxyType.HTTP -> ProxyType.HTTP
        ExchangeProxyType.SOCKS -> ProxyType.SOCKS
    }
}
