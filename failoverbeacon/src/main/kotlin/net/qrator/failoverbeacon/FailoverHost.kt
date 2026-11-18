package net.qrator.failoverbeacon

import java.net.Inet4Address
import net.qrator.failoverbeacon.core.config.ConfigRules

/**
 * A host whose connections the SDK speeds up. Pass it to
 * [FailoverBeaconConfig.Builder.failoverHosts].
 *
 * [host] is a host name as in request URLs, case-insensitive, in Unicode or Punycode.
 * [port] is used for test connections, 443 by default.
 * [additionalAddresses] are IPv4 addresses of the host known to the app, such as `192.0.2.10`.
 * The SDK uses them along with DNS results and when DNS fails.
 * [tcpProbe] allows test connections to the host's addresses, true by default. New
 * connections then prefer the fastest address, whatever balancing DNS does.
 * [bodyProbe] also checks that the first addresses deliver a response, none by default. It needs
 * [tcpProbe].
 *
 * @throws IllegalArgumentException if [host] is not a host name, [port] is outside 1..65535,
 * [additionalAddresses] has a value that is not an IPv4 address or [bodyProbe] is set without
 * [tcpProbe].
 */
public class FailoverHost @JvmOverloads public constructor(
    host: String,
    port: Int = ConfigRules.DEFAULT_PORT,
    additionalAddresses: List<String> = emptyList(),
    internal val tcpProbe: Boolean = ConfigRules.DEFAULT_TCP_PROBE,
    internal val bodyProbe: FailoverBodyProbe? = null
) {
    init {
        ConfigRules.checkBodyProbeNeedsTcpProbe(tcpProbe, bodyProbe != null)
    }

    internal val host: String = ConfigRules.checkFailoverHost(host)

    internal val port: Int = ConfigRules.checkPort(port)

    internal val additionalAddresses: List<Inet4Address> = additionalAddresses.distinct().map {
        ConfigRules.checkAdditionalAddress(it, this.host)
    }
}
