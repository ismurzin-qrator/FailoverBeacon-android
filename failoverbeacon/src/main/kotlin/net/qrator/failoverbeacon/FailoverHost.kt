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
 * [probeConnections] allows test connections to the host's addresses, true by default. New
 * connections then prefer the fastest address, whatever balancing DNS does.
 *
 * @throws IllegalArgumentException if [host] is not a host name, [port] is outside 1..65535 or
 * [additionalAddresses] has a value that is not an IPv4 address.
 */
public class FailoverHost @JvmOverloads public constructor(
    host: String,
    port: Int = 443,
    additionalAddresses: List<String> = emptyList(),
    internal val probeConnections: Boolean = true
) {
    internal val host: String = requireNotNull(canonicalHost(host)) {
        "host must be a host name without scheme, port or path, was \"$host\""
    }.also { name ->
        require(!endsInNumber(name)) {
            "host must be a name, not an IP address, was \"$host\""
        }
    }

    internal val port: Int = port.also {
        require(it in 1..65535) { "port must be in 1..65535, was $it" }
    }

    internal val additionalAddresses: List<Inet4Address> = additionalAddresses.distinct().map {
        requireNotNull(parseIpv4(it, this.host)) {
            "additionalAddresses must contain IPv4 addresses like 192.0.2.10, was \"$it\""
        }
    }
}
