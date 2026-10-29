package net.qrator.failoverbeacon.okhttp.internal

import net.qrator.failoverbeacon.okhttp.internal.util.SuppressSignatureCheck
import okhttp3.Dns

internal object FailoverBeaconDnsFactory {
    fun create(delegate: Dns): Dns = if (isNewCallDnsApi) {
        FailoverBeaconDnsWithNewCall(delegate)
    } else {
        FailoverBeaconDns(delegate)
    }

    private val isNewCallDnsApi = hasNewCallDnsApi()

    @SuppressSignatureCheck(since = "5.5")
    private fun hasNewCallDnsApi(): Boolean = try {
        Dns.Request::class.java.name.isNotEmpty()
    } catch (_: LinkageError) {
        false
    }
}
