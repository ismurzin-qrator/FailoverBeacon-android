package net.qrator.failoverbeacon.okhttp.internal

import java.io.IOException
import java.net.UnknownHostException
import net.qrator.failoverbeacon.okhttp.internal.util.SuppressSignatureCheck
import okhttp3.Dns

@SuppressSignatureCheck(since = "5.5")
internal class EchDnsCall(
    override val request: Dns.Request,
    private val dns: Dns,
    private val serviceMetadata: ServiceMetadataCache,
    private val isFallbackWithoutEchAllowed: Boolean
) : Dns.Call {
    @Volatile private var isCanceled = false

    override fun enqueue(callback: Dns.Callback) {
        val records = try {
            val metadata = serviceMetadata.getAndRefresh(request)
            if (metadata == null && !isFallbackWithoutEchAllowed) {
                throw UnknownHostException("${request.hostname}: no answer from the app's DNS")
            }
            val addresses = dns.lookup(request.hostname)
            addresses.map { Dns.Record.IpAddress(request.hostname, it) } + metadata.orEmpty()
        } catch (e: UnknownHostException) {
            callback.onFailure(this, e)
            return
        }
        if (isCanceled) {
            callback.onFailure(this, IOException("canceled"))
        } else {
            callback.onRecords(this, last = true, records = records)
        }
    }

    override fun cancel() {
        isCanceled = true
    }

    override fun isCanceled(): Boolean = isCanceled
}
