package net.qrator.failoverbeacon.okhttp.internal

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.dns.SdkDns
import net.qrator.failoverbeacon.okhttp.fakes.installSdk
import okhttp3.Dns

class FailoverBeaconDnsTest {
    private val appLookups = mutableListOf<String>()
    private var appAnswer: () -> List<InetAddress> = { listOf(ip(1)) }
    private val appDns = Dns { host ->
        appLookups += host
        appAnswer()
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
    fun `without the SDK the app's DNS answers`() {
        SdkRegistry.clear()

        assertEquals(listOf(ip(1)), dns.lookup(API))
    }

    @Test
    fun `without SDK DNS the app's DNS answers`() {
        assertNotNull(SdkRegistry.instance).dns = null

        assertEquals(listOf(ip(1)), dns.lookup(API))
    }

    @Test
    fun `the SDK addresses are used without the app's DNS`() {
        sdkDns.addresses = listOf(ip(5))

        assertEquals(listOf(ip(5)), dns.lookup(API))
        assertEquals(emptyList(), appLookups)
    }

    @Test
    fun `when the SDK has no addresses the app's DNS answers`() {
        assertEquals(listOf(ip(1)), dns.lookup(API))
        assertEquals(listOf(API), sdkDns.lookups)
    }

    @Test
    fun `when the SDK has no addresses the app's DNS error is thrown`() {
        val error = UnknownHostException(API)
        appAnswer = { throw error }

        assertSame(error, assertFailsWith<UnknownHostException> { dns.lookup(API) })
    }

    @Test
    fun `an SDK failure falls back to the app's DNS`() {
        sdkDns.addresses = listOf(ip(5))
        sdkDns.failure = IllegalStateException()

        assertEquals(listOf(ip(1)), dns.lookup(API))
    }

    @Test
    fun `an SDK of another version falls back to the app's DNS`() {
        sdkDns.failure = NoSuchMethodError()

        assertEquals(listOf(ip(1)), dns.lookup(API))
    }

    @Test
    fun `the wrapper names the app's DNS`() {
        assertEquals("FailoverBeaconDns($appDns)", dns.toString())
    }

    private class FakeSdkDns : SdkDns {
        var addresses: List<InetAddress>? = null
        var failure: Throwable? = null
        val lookups = mutableListOf<String>()

        override fun manages(host: String): Boolean = host == API

        override fun lookup(host: String): List<InetAddress>? {
            failure?.let { throw it }
            lookups += host
            return addresses
        }
    }

    private companion object {
        const val API = "api.example"

        fun ip(last: Int): InetAddress =
            InetAddress.getByAddress(API, byteArrayOf(192.toByte(), 0, 2, last.toByte()))
    }
}
