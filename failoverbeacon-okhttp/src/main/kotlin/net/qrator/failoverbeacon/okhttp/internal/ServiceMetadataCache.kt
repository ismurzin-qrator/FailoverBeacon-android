package net.qrator.failoverbeacon.okhttp.internal

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import net.qrator.failoverbeacon.okhttp.internal.util.SuppressSignatureCheck
import okhttp3.Dns

@SuppressSignatureCheck(since = "5.5")
internal class ServiceMetadataCache(private val dns: Dns) {
    private val cache = ConcurrentHashMap<Dns.Request, List<Dns.Record.ServiceMetadata>>()
    private val refreshing = ConcurrentHashMap.newKeySet<Dns.Request>()

    fun getAndRefresh(request: Dns.Request): List<Dns.Record.ServiceMetadata> {
        val cached = cache[request]
        if (cached != null) {
            if (refreshing.add(request)) query(request) { refreshing.remove(request) }
            return cached
        }
        val done = CountDownLatch(1)
        query(request, done::countDown)
        done.await()
        return cache[request].orEmpty()
    }

    private fun query(request: Dns.Request, onDone: () -> Unit) {
        dns.newCall(request).enqueue(
            object : Dns.Callback {
                private val found = mutableListOf<Dns.Record.ServiceMetadata>()

                override fun onRecords(call: Dns.Call, last: Boolean, records: List<Dns.Record>) {
                    found += records.filterIsInstance<Dns.Record.ServiceMetadata>()
                    if (last) {
                        cache[request] = found.toList()
                        onDone()
                    }
                }

                override fun onFailure(call: Dns.Call, e: IOException) {
                    cache.putIfAbsent(request, found.toList())
                    onDone()
                }
            }
        )
    }
}
