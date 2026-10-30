package net.qrator.failoverbeacon.okhttp.internal

import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.dns.SdkDns
import net.qrator.failoverbeacon.okhttp.fakes.installSdk
import net.qrator.failoverbeacon.okhttp.withFailoverBeacon
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue

class FailoverBeaconDnsWithNewCallTest {
    private val hasNewCall = runCatching { Class.forName("okhttp3.Dns\$Request") }.isSuccess

    @BeforeTest
    fun setUp() {
        installSdk()
        SdkRegistry.instance?.dns = ManagesApi()
    }

    @AfterTest
    fun tearDown() {
        SdkRegistry.clear()
    }

    @Test
    fun `the wrapper overrides newCall only on OkHttp with newCall`() {
        val dns = OkHttpClient().withFailoverBeacon().dns

        assertEquals(hasNewCall, dns is FailoverBeaconDnsWithNewCall)
        assertIs<FailoverBeaconDns>(dns)
    }

    @Test
    fun `hosts outside the config get the app's own DNS call`() {
        assumeTrue(hasNewCall)
        val appDns = AppDns()

        val call = FailoverBeaconDnsWithNewCall(appDns).newCall(Dns.Request(OTHER))

        assertSame(appDns.call, call)
    }

    @Test
    fun `configured hosts are looked up through the SDK`() {
        assumeTrue(hasNewCall)
        val appDns = AppDns()

        val call = FailoverBeaconDnsWithNewCall(appDns).newCall(Dns.Request(API))

        assertNotSame(appDns.call, call)
        assertEquals(API, call.request.hostname)
    }

    @Test
    fun `without the SDK every host gets the app's own DNS call`() {
        assumeTrue(hasNewCall)
        SdkRegistry.clear()
        val appDns = AppDns()

        assertSame(appDns.call, FailoverBeaconDnsWithNewCall(appDns).newCall(Dns.Request(API)))
    }

    private class AppDns : Dns {
        val call = object : Dns.Call {
            override val request = Dns.Request(OTHER)

            override fun enqueue(callback: Dns.Callback) = Unit

            override fun cancel() = Unit

            override fun isCanceled() = false
        }

        override fun lookup(hostname: String): List<InetAddress> = emptyList()

        override fun newCall(request: Dns.Request): Dns.Call = call
    }

    private class ManagesApi : SdkDns {
        override fun manages(host: String) = host == API

        override fun lookup(host: String): List<InetAddress>? = null
    }

    private companion object {
        const val API = "api.test"
        const val OTHER = "other.test"
    }
}
