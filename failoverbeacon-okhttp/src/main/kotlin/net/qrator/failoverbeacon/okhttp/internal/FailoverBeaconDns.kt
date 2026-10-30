package net.qrator.failoverbeacon.okhttp.internal

import java.net.InetAddress
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.error
import net.qrator.failoverbeacon.core.util.catchSdkFailure
import okhttp3.Dns

internal open class FailoverBeaconDns(protected val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        catchSdkFailure(::logFailure) {
            SdkRegistry.instance?.dns?.lookup(hostname)?.let { return it }
        }
        return delegate.lookup(hostname)
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
