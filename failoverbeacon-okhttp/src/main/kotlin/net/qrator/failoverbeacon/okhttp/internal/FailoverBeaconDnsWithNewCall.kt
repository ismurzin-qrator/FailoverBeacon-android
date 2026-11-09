package net.qrator.failoverbeacon.okhttp.internal

import android.os.Build
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.okhttp.internal.util.SuppressSignatureCheck
import okhttp3.Dns

@SuppressSignatureCheck(since = "5.5")
internal class FailoverBeaconDnsWithNewCall(delegate: Dns) : FailoverBeaconDns(delegate) {
    private val serviceMetadata = ServiceMetadataCache(delegate)
    private val isEchSupported = Build.VERSION.SDK_INT >= ECH_MIN_API
    private val delegateHasOwnNewCall by lazy {
        delegate.newCall(PROBE).javaClass != LOOKUP_ONLY.newCall(PROBE).javaClass
    }

    override fun newCall(request: Dns.Request): Dns.Call {
        val sdkDns = SdkRegistry.instance?.dns
        return when {
            sdkDns == null || !sdkDns.manages(request.hostname) -> delegate.newCall(request)

            isEchSupported && delegateHasOwnNewCall ->
                EchDnsCall(request, this, serviceMetadata, sdkDns.isFallbackWithoutEchAllowed)

            else -> super.newCall(request)
        }
    }

    @SuppressSignatureCheck(since = "5.5")
    private companion object {
        // OkHttp applies ECH on Android 17.
        const val ECH_MIN_API = 37
        val PROBE = Dns.Request("localhost")
        val LOOKUP_ONLY = Dns { emptyList() }
    }
}
