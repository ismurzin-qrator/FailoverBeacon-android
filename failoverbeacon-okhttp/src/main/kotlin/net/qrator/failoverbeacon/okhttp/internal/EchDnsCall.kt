package net.qrator.failoverbeacon.okhttp.internal

import java.io.IOException
import java.net.UnknownHostException
import net.qrator.failoverbeacon.okhttp.internal.util.SuppressSignatureCheck
import okhttp3.Dns

@SuppressSignatureCheck(since = "5.5")
internal class EchDnsCall(
    override val request: Dns.Request,
    private val dns: Dns,
    private val serviceMetadata: ServiceMetadataCache
) : Dns.Call {
    @Volatile private var canceled = false

    override fun enqueue(callback: Dns.Callback) {
        val records = try {
            val metadata = serviceMetadata.get(request)
            val addresses = dns.lookup(request.hostname)
            addresses.map { Dns.Record.IpAddress(request.hostname, it) } + metadata
        } catch (e: UnknownHostException) {
            callback.onFailure(this, e)
            return
        }
        if (canceled) {
            callback.onFailure(this, IOException("canceled"))
        } else {
            callback.onRecords(this, last = true, records = records)
        }
    }

    override fun cancel() {
        canceled = true
    }

    override fun isCanceled(): Boolean = canceled
}
