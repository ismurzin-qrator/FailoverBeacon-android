package net.qrator.failoverbeacon.okhttp.internal

import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.okhttp.internal.util.SuppressSignatureCheck
import okhttp3.Dns

@SuppressSignatureCheck(since = "5.5")
internal class FailoverBeaconDnsWithNewCall(delegate: Dns) : FailoverBeaconDns(delegate) {
    override fun newCall(request: Dns.Request): Dns.Call {
        val isManagedBySdk = SdkRegistry.instance?.dns?.manages(request.hostname) ?: false
        return if (isManagedBySdk) super.newCall(request) else delegate.newCall(request)
    }
}
