package net.qrator.failoverbeacon

import java.net.Inet4Address
import net.qrator.failoverbeacon.internal.util.canonicalHost
import net.qrator.failoverbeacon.internal.util.endsInNumber
import net.qrator.failoverbeacon.internal.util.parseIpv4

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
    port: Int = HTTPS_PORT,
    additionalAddresses: List<String> = emptyList(),
    internal val tcpProbe: Boolean = true,
    internal val bodyProbe: FailoverBodyProbe? = null
) {
    init {
        require(tcpProbe || bodyProbe == null) { "bodyProbe needs tcpProbe" }
    }

    internal val host: String = requireNotNull(canonicalHost(host)) {
        "host must be a host name without scheme, port or path, was \"$host\""
    }.also { name ->
        require(!endsInNumber(name)) {
            "host must be a name, not an IP address, was \"$host\""
        }
    }

    internal val port: Int = port.also {
        require(it in 1..MAX_PORT) { "port must be in 1..$MAX_PORT, was $it" }
    }

    internal val additionalAddresses: List<Inet4Address> = additionalAddresses.distinct().map {
        requireNotNull(parseIpv4(it, this.host)) {
            "additionalAddresses must contain IPv4 addresses like 192.0.2.10, was \"$it\""
        }
    }

    private companion object {
        const val HTTPS_PORT = 443
        const val MAX_PORT = 65535
    }
}
