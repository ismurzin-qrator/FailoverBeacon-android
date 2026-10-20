package net.qrator.failoverbeacon.telemetry.mapper

import net.qrator.failoverbeacon.core.call.model.ProxyType
import net.qrator.failoverbeacon.telemetry.model.TelemetryProxyType

internal class TelemetryProxyTypeMapper {
    fun toProxyType(proxy: ProxyType): TelemetryProxyType = when (proxy) {
        ProxyType.DIRECT -> TelemetryProxyType.DIRECT
        ProxyType.HTTP -> TelemetryProxyType.HTTP
        ProxyType.SOCKS -> TelemetryProxyType.SOCKS
    }
}
