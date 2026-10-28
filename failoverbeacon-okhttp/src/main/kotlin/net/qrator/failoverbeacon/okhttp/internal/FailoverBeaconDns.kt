package net.qrator.failoverbeacon.okhttp.internal

import java.net.InetAddress
import java.net.UnknownHostException
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.util.catchSdkFailure
import okhttp3.Dns

internal class FailoverBeaconDns(private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val dns = SdkRegistry.instance?.dns ?: return delegate.lookup(hostname)
        catchSdkFailure(::logFailure) {
            dns.preparedAddresses(hostname)?.let { return it }
        }
        val addresses = try {
            delegate.lookup(hostname)
        } catch (e: UnknownHostException) {
            catchSdkFailure(::logFailure) {
                dns.onResolveFailed(hostname)?.let { return it }
            }
            throw e
        }
        catchSdkFailure(::logFailure) {
            return dns.onResolved(hostname, addresses)
        }
        return addresses
    }

    override fun equals(other: Any?): Boolean =
        other is FailoverBeaconDns && other.delegate == delegate

    override fun hashCode(): Int = delegate.hashCode()

    private fun logFailure(failure: Throwable) {
        SdkRegistry.logger.error(failure, onlyOnce = true) {
            "[OkHttp] SDK DNS failed. The client's DNS is used. Further failures are not logged"
        }
    }
}
