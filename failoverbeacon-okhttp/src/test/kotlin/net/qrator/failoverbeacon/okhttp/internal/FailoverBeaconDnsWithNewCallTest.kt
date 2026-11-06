package net.qrator.failoverbeacon.okhttp.internal

import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `hosts the SDK does not manage get the app's own DNS call`() {
        assumeTrue(hasNewCall)
        val appDns = AppDns()
        val other = FailoverBeaconDnsWithNewCall(appDns).newCall(Dns.Request(OTHER))
        SdkRegistry.clear()

        val withoutSdk = FailoverBeaconDnsWithNewCall(appDns).newCall(Dns.Request(API))

        assertSame(appDns.call, other)
        assertSame(appDns.call, withoutSdk)
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
