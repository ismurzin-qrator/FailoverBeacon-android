package net.qrator.failoverbeacon.okhttp.internal

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import net.qrator.failoverbeacon.okhttp.internal.util.SuppressSignatureCheck
import okhttp3.Dns

@SuppressSignatureCheck(since = "5.5")
internal class ServiceMetadataCache(private val dns: Dns) {
    private val answered = ConcurrentHashMap<Dns.Request, List<Dns.Record.ServiceMetadata>>()
    private val unanswered = ConcurrentHashMap.newKeySet<Dns.Request>()
    private val refreshing = ConcurrentHashMap.newKeySet<Dns.Request>()

    fun getAndRefresh(request: Dns.Request): List<Dns.Record.ServiceMetadata>? {
        if (answered.containsKey(request) || request in unanswered) {
            if (refreshing.add(request)) query(request) { refreshing.remove(request) }
            return answered[request]
        }
        val done = CountDownLatch(1)
        query(request, done::countDown)
        done.await()
        return answered[request]
    }

    private fun query(request: Dns.Request, onDone: () -> Unit) {
        dns.newCall(request).enqueue(
            object : Dns.Callback {
                private val found = mutableListOf<Dns.Record.ServiceMetadata>()
                private var hasAddresses = false

                override fun onRecords(call: Dns.Call, last: Boolean, records: List<Dns.Record>) {
                    found += records.filterIsInstance<Dns.Record.ServiceMetadata>()
                    hasAddresses = hasAddresses || records.any { it is Dns.Record.IpAddress }
                    if (last) finish()
                }

                override fun onFailure(call: Dns.Call, e: IOException) = finish()

                private fun finish() {
                    if (hasAddresses || found.isNotEmpty()) {
                        answered[request] = found.toList()
                        unanswered -= request
                    } else if (!answered.containsKey(request)) {
                        unanswered += request
                    }
                    onDone()
                }
            }
        )
    }
}
