package net.qrator.failoverbeacon.okhttp.internal

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.dns.SdkDns
import net.qrator.failoverbeacon.okhttp.fakes.installSdk
import okhttp3.Dns
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class FailoverBeaconDnsEchTest {
    private val hasNewCall = runCatching { Class.forName("okhttp3.Dns\$Request") }.isSuccess
    private val sdkDns = SdkAddresses()
    private val appDns = AppDns()

    @BeforeTest
    fun setUp() {
        assumeTrue(hasNewCall)
        installSdk()
        SdkRegistry.instance?.dns = sdkDns
    }

    @AfterTest
    fun tearDown() {
        SdkRegistry.clear()
    }

    @Test
    fun `a configured host gets the SDK addresses with the metadata of the app's DNS`() {
        assertEquals(listOf(ipRecord(SDK_IP), metadata()), records())
    }

    @Test
    fun `the first lookup waits for the metadata and later ones refresh it in the background`() {
        val dns = FailoverBeaconDnsWithNewCall(appDns)
        records(dns)
        appDns.isAnswering = false

        val cached = records(dns)
        records(dns)

        assertEquals(listOf(ipRecord(SDK_IP), metadata()), cached)
        assertEquals(2, appDns.calls)
        assertEquals(1, appDns.pending.size)
    }

    @Test
    fun `a refresh replaces the metadata`() {
        val dns = FailoverBeaconDnsWithNewCall(appDns)
        records(dns)
        appDns.isAnswering = false
        records(dns)

        appDns.answerPending(listOf(metadata(port = 8443)))

        assertEquals(listOf(ipRecord(SDK_IP), metadata(port = 8443)), records(dns))
    }

    @Test
    fun `when the app's DNS fails the SDK addresses come without metadata and are not delayed`() {
        val dns = FailoverBeaconDnsWithNewCall(appDns)
        appDns.isFailing = true
        val first = records(dns)
        appDns.isAnswering = false

        val second = records(dns)

        assertEquals(listOf(ipRecord(SDK_IP)), first)
        assertEquals(listOf(ipRecord(SDK_IP)), second)
    }

    @Test
    fun `without SDK addresses the app's addresses come with the metadata`() {
        sdkDns.addresses = null

        assertEquals(listOf(ipRecord(APP_IP), metadata()), records())
    }

    @Test
    fun `without any addresses the app's error is reported`() {
        sdkDns.addresses = null
        appDns.isFailing = true

        assertFailsWith<UnknownHostException> { records() }
    }

    @Test
    fun `a canceled call fails`() {
        val call = FailoverBeaconDnsWithNewCall(appDns).newCall(Dns.Request(API))
        call.cancel()

        assertFailsWith<IOException> { call.records() }
    }

    @Test
    fun `the system DNS is not asked for metadata`() {
        val call = FailoverBeaconDnsWithNewCall(Dns.SYSTEM).newCall(Dns.Request(API))

        assertFalse(call is EchDnsCall)
    }

    @Test
    @Config(sdk = [24])
    fun `below Android 17 the app's DNS is not asked for metadata`() {
        val call = FailoverBeaconDnsWithNewCall(appDns).newCall(Dns.Request(API))

        assertFalse(call is EchDnsCall)
        assertEquals(0, appDns.calls)
    }

    private fun records(): List<Dns.Record> = records(FailoverBeaconDnsWithNewCall(appDns))

    private class SdkAddresses : SdkDns {
        var addresses: List<InetAddress>? = listOf(SDK_IP)

        override fun manages(host: String): Boolean = host == API

        override fun lookup(host: String): List<InetAddress>? = addresses
    }

    private class AppDns : Dns {
        var isAnswering = true
        var isFailing = false
        var calls = 0
        val pending = mutableListOf<Pair<Dns.Call, Dns.Callback>>()

        fun answerPending(records: List<Dns.Record>) {
            pending.forEach { (call, callback) -> callback.onRecords(call, true, records) }
            pending.clear()
        }

        override fun lookup(hostname: String): List<InetAddress> {
            if (isFailing) throw UnknownHostException(hostname)
            return listOf(APP_IP)
        }

        override fun newCall(request: Dns.Request): Dns.Call = object : Dns.Call {
            override val request = request

            override fun enqueue(callback: Dns.Callback) {
                calls++
                when {
                    !isAnswering -> pending += this to callback
                    isFailing -> callback.onFailure(this, UnknownHostException(request.hostname))
                    else -> callback.onRecords(this, true, listOf(ipRecord(APP_IP), metadata()))
                }
            }

            override fun cancel() = Unit

            override fun isCanceled() = false
        }
    }

    private companion object {
        const val API = "api.test"
        val SDK_IP: InetAddress = InetAddress.getByName("192.0.2.1")
        val APP_IP: InetAddress = InetAddress.getByName("192.0.2.2")

        fun ipRecord(address: InetAddress) = Dns.Record.IpAddress(API, address)

        fun metadata(port: Int = 443) = Dns.Record.ServiceMetadata(API, port = port)
    }
}

private fun records(dns: Dns): List<Dns.Record> {
    val call = dns.newCall(Dns.Request("api.test"))
    assertIs<EchDnsCall>(call)
    return call.records()
}

private fun Dns.Call.records(): List<Dns.Record> {
    var result: Result<List<Dns.Record>>? = null
    enqueue(
        object : Dns.Callback {
            override fun onRecords(call: Dns.Call, last: Boolean, records: List<Dns.Record>) {
                result = Result.success(records)
            }

            override fun onFailure(call: Dns.Call, e: IOException) {
                result = Result.failure(e)
            }
        }
    )
    return checkNotNull(result).getOrThrow()
}
