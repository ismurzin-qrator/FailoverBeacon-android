package net.qrator.failoverbeacon.internal.network

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.os.Build
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot
import net.qrator.failoverbeacon.core.network.model.NetworkTransport
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class NetworkMonitorTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val monitor = NetworkMonitor(app)

    @AfterTest
    fun tearDown() {
        SdkRegistry.logger.output = null
    }

    @Test
    fun `reports what the system says about the default network`() {
        val cases = listOf(
            capabilities(
                transports = listOf(TRANSPORT_WIFI),
                capabilities = listOf(NET_CAPABILITY_VALIDATED, NET_CAPABILITY_NOT_METERED)
            ) to BASE.copy(transport = NetworkTransport.WIFI, validated = true, metered = false),
            capabilities(transports = listOf(TRANSPORT_CELLULAR), roaming = true) to
                BASE.copy(transport = NetworkTransport.CELLULAR, roaming = true),
            capabilities(transports = listOf(TRANSPORT_ETHERNET)) to
                BASE.copy(transport = NetworkTransport.ETHERNET),
            capabilities(transports = listOf(TRANSPORT_BLUETOOTH)) to
                BASE.copy(transport = NetworkTransport.BLUETOOTH),
            capabilities(transports = listOf(TRANSPORT_VPN, TRANSPORT_WIFI)) to
                BASE.copy(transport = NetworkTransport.WIFI, vpn = true),
            capabilities(transports = listOf(TRANSPORT_VPN)) to
                BASE.copy(transport = NetworkTransport.OTHER, vpn = true),
            capabilities(
                transports = listOf(TRANSPORT_WIFI),
                capabilities = listOf(NET_CAPABILITY_CAPTIVE_PORTAL)
            ) to BASE.copy(transport = NetworkTransport.WIFI, captivePortal = true),
            capabilities(transports = listOf(TRANSPORT_WIFI), downKbps = 30_000, upKbps = 10_000) to
                BASE.copy(
                    transport = NetworkTransport.WIFI,
                    downstreamKbps = 30_000,
                    upstreamKbps = 10_000
                )
        )
        for ((capabilities, expected) in cases) {
            monitor.onCapabilitiesChanged(WIFI_NETWORK, capabilities)

            assertEquals(expected, monitor.currentNetwork())
        }
    }

    @Test
    @Config(sdk = [27])
    fun `roaming is unknown below API 28`() {
        monitor.onCapabilitiesChanged(WIFI_NETWORK, capabilities(listOf(TRANSPORT_CELLULAR)))

        assertNull(assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork()).roaming)
    }

    @Test
    @Config(sdk = [28])
    fun `signal strength is unknown below API 29`() {
        monitor.onCapabilitiesChanged(WIFI_NETWORK, capabilities(signalStrength = -60))

        assertNull(assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork()).signalStrength)
    }

    @Test
    @Config(sdk = [29])
    fun `signal strength is reported from API 29`() {
        monitor.onCapabilitiesChanged(WIFI_NETWORK, capabilities(signalStrength = -60))

        assertEquals(
            -60,
            assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork()).signalStrength
        )
    }

    @Test
    @Config(sdk = [24])
    fun `below API 26 a new default network is read when it becomes available`() {
        shadowOf(app.getSystemService(ConnectivityManager::class.java))
            .setNetworkCapabilities(WIFI_NETWORK, capabilities(listOf(TRANSPORT_WIFI)))
        monitor.register()

        monitor.onAvailable(WIFI_NETWORK)
        val available = monitor.currentNetwork()
        monitor.onLost(WIFI_NETWORK)

        assertEquals(
            NetworkTransport.WIFI,
            assertIs<NetworkSnapshot.Connected>(available).transport
        )
        assertEquals(NetworkSnapshot.None, monitor.currentNetwork())
    }

    @Test
    @Config(sdk = [26])
    fun `from API 26 a new default network waits for its capabilities`() {
        shadowOf(app.getSystemService(ConnectivityManager::class.java))
            .setNetworkCapabilities(WIFI_NETWORK, capabilities(listOf(TRANSPORT_WIFI)))
        monitor.register()

        monitor.onAvailable(WIFI_NETWORK)

        assertEquals(NetworkSnapshot.Unknown, monitor.currentNetwork())
    }

    @Test
    fun `losing the default network leaves none`() {
        monitor.onCapabilitiesChanged(WIFI_NETWORK, capabilities(listOf(TRANSPORT_WIFI)))
        monitor.onLost(WIFI_NETWORK)

        assertEquals(NetworkSnapshot.None, monitor.currentNetwork())
    }

    @Test
    @Config(shadows = [RefusingConnectivityManager::class])
    fun `a refused registration leaves the network unknown and is logged`() {
        val logged = mutableListOf<Throwable?>()
        SdkRegistry.logger.output = LogOutput { _, _, throwable -> logged += throwable }

        monitor.register()

        assertEquals(NetworkSnapshot.Unknown, monitor.currentNetwork())
        assertIs<SecurityException>(logged.single())
    }

    private fun capabilities(
        transports: List<Int> = emptyList(),
        capabilities: List<Int> = emptyList(),
        roaming: Boolean = false,
        downKbps: Int = 0,
        upKbps: Int = 0,
        signalStrength: Int? = null
    ): NetworkCapabilities {
        val result = ShadowNetworkCapabilities.newInstance()
        val shadow = shadowOf(result)
        transports.forEach(shadow::addTransportType)
        capabilities.forEach(shadow::addCapability)
        if (!roaming && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            shadow.addCapability(NET_CAPABILITY_NOT_ROAMING)
        }
        shadow.setLinkDownstreamBandwidthKbps(downKbps)
        shadow.setLinkUpstreamBandwidthKbps(upKbps)
        // The setter is hidden from apps.
        signalStrength?.let {
            NetworkCapabilities::class.java.getMethod("setSignalStrength", Int::class.java)
                .invoke(result, it)
        }
        return result
    }

    @Implements(ConnectivityManager::class)
    class RefusingConnectivityManager {
        @Implementation
        fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback): Unit =
            throw SecurityException()
    }

    private companion object {
        val BASE = NetworkSnapshot.Connected(
            transport = NetworkTransport.OTHER,
            vpn = false,
            validated = false,
            captivePortal = false,
            metered = true,
            roaming = false,
            downstreamKbps = null,
            upstreamKbps = null,
            signalStrength = null
        )
        val WIFI_NETWORK = ShadowNetwork.newInstance(100)
    }
}
