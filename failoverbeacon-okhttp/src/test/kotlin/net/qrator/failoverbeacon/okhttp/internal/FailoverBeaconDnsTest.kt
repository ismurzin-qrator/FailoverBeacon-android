package net.qrator.failoverbeacon.okhttp.internal

import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.dns.SdkDns
import net.qrator.failoverbeacon.okhttp.fakes.installSdk
import okhttp3.Dns

class FailoverBeaconDnsTest {
    private val appLookups = mutableListOf<String>()
    private val appDns = Dns { host ->
        appLookups += host
        listOf(ip(1))
    }
    private val sdkDns = FakeSdkDns()
    private val dns = FailoverBeaconDns(appDns)

    @BeforeTest
    fun setUp() {
        installSdk()
        assertNotNull(SdkRegistry.instance).dns = sdkDns
    }

    @AfterTest
    fun tearDown() {
        SdkRegistry.clear()
    }

    @Test
    fun `the SDK addresses are used without the app's DNS`() {
        sdkDns.addresses = listOf(ip(5))

        assertEquals(listOf(ip(5)), dns.lookup(API))
        assertEquals(emptyList(), appLookups)
    }

    @Test
    fun `an SDK failure or an SDK of another version falls back to the app's DNS`() {
        sdkDns.addresses = listOf(ip(5))

        for (failure in listOf(IllegalStateException(), NoSuchMethodError())) {
            sdkDns.failure = failure

            assertEquals(listOf(ip(1)), dns.lookup(API), "$failure")
        }
    }

    private class FakeSdkDns : SdkDns {
        override val isFallbackWithoutEchAllowed = true

        var addresses: List<InetAddress>? = null
        var failure: Throwable? = null

        override fun manages(host: String): Boolean = host == API

        override fun lookup(host: String): List<InetAddress>? {
            failure?.let { throw it }
            return addresses
        }
    }

    private companion object {
        const val API = "api.example"

        fun ip(last: Int): InetAddress =
            InetAddress.getByAddress(API, byteArrayOf(192.toByte(), 0, 2, last.toByte()))
    }
}
