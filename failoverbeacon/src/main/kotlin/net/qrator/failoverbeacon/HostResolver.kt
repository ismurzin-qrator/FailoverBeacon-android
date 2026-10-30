package net.qrator.failoverbeacon

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Resolves the hosts of [FailoverBeaconConfig.Builder.failoverHosts]. Set it with
 * [FailoverBeaconConfig.Builder.dnsResolver]. Called on SDK threads and on the threads of
 * HTTP clients, so it must be safe for concurrent use.
 */
public fun interface HostResolver {
    /** Returns the addresses of [host]. */
    @Throws(UnknownHostException::class)
    public fun resolve(host: String): List<InetAddress>
}
