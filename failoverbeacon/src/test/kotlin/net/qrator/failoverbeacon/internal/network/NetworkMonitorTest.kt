package net.qrator.failoverbeacon.internal.network

import android.Manifest
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
import android.telephony.TelephonyManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.core.network.model.NetworkCarrier
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot
import net.qrator.failoverbeacon.core.network.model.NetworkTransport
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSubscriptionManager

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

            val network = assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork())
            assertEquals(expected, network.copy(carrier = null))
        }
    }

    @Test
    fun `reads the carrier of the SIM that carries mobile data`() {
        voiceSim(operator = "25001", name = "MTS", carrierId = 1)
        val dataSim = sim(operator = "25099", name = "Beeline", carrierId = 2)
        shadowOf(dataSim).setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
        shadowOf(telephonyManager).setTelephonyManagerForSubscriptionId(DATA_SUBSCRIPTION, dataSim)
        ShadowSubscriptionManager.setActiveDataSubscriptionId(DATA_SUBSCRIPTION)
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)

        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, capabilities(listOf(TRANSPORT_CELLULAR)))

        assertEquals(
            NetworkCarrier("Beeline", "250", "99", 2, TelephonyManager.NETWORK_TYPE_LTE),
            assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork()).carrier
        )
    }

    @Test
    @Config(sdk = [29])
    fun `below API 30 the default data SIM is read`() {
        voiceSim(operator = "25001", name = "MTS", carrierId = 1)
        val dataSim = sim(operator = "25099", name = "Beeline", carrierId = 2)
        shadowOf(telephonyManager).setTelephonyManagerForSubscriptionId(DATA_SUBSCRIPTION, dataSim)
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(DATA_SUBSCRIPTION)

        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, capabilities(listOf(TRANSPORT_CELLULAR)))

        assertEquals(
            "Beeline",
            assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork()).carrier?.name
        )
    }

    @Test
    @Config(sdk = [27])
    fun `below API 28 the carrier is named by its SIM and has no id`() {
        shadowOf(telephonyManager).apply {
            setSimOperator("25001")
            setSimOperatorName("MTS")
        }

        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, capabilities(listOf(TRANSPORT_CELLULAR)))

        assertEquals(
            NetworkCarrier("MTS", "250", "01", carrierId = null, networkType = null),
            assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork()).carrier
        )
    }

    @Test
    fun `the network type needs the phone state permission`() {
        voiceSim(operator = "25001", name = "MTS", carrierId = 1)
        shadowOf(telephonyManager).setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE)

        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, capabilities(listOf(TRANSPORT_CELLULAR)))

        assertEquals(
            NetworkCarrier("MTS", "250", "01", 1, networkType = null),
            assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork()).carrier
        )
    }

    @Test
    fun `there is no carrier off cellular networks`() {
        voiceSim(operator = "25001", name = "MTS", carrierId = 1)

        monitor.onCapabilitiesChanged(WIFI_NETWORK, capabilities(listOf(TRANSPORT_WIFI)))

        assertNull(assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork()).carrier)
    }

    @Test
    @Config(shadows = [UnsupportedTelephonyManager::class])
    fun `a device without telephony reports the network without a carrier`() {
        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, capabilities(listOf(TRANSPORT_CELLULAR)))

        val network = assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork())
        assertEquals(NetworkTransport.CELLULAR, network.transport)
        assertNull(network.carrier)
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
    fun `reports whether the system blocks the network for the app`() {
        val wifi = capabilities(listOf(TRANSPORT_WIFI))
        monitor.onCapabilitiesChanged(WIFI_NETWORK, wifi)
        val unreported = assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork()).blocked
        monitor.onBlockedStatusChanged(WIFI_NETWORK, true)
        monitor.onCapabilitiesChanged(
            WIFI_NETWORK,
            capabilities(listOf(TRANSPORT_WIFI), downKbps = 1)
        )

        assertNull(unreported)
        assertEquals(true, assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork()).blocked)
    }

    @Test
    fun `a new default network does not keep the blocking of the previous one`() {
        monitor.onCapabilitiesChanged(WIFI_NETWORK, capabilities(listOf(TRANSPORT_WIFI)))
        monitor.onBlockedStatusChanged(WIFI_NETWORK, true)
        monitor.onAvailable(CELLULAR_NETWORK)
        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, capabilities(listOf(TRANSPORT_CELLULAR)))

        assertNull(assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork()).blocked)
    }

    @Test
    fun `blocking reported without a network leaves it unknown`() {
        monitor.onBlockedStatusChanged(WIFI_NETWORK, true)

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

    private val telephonyManager: TelephonyManager
        get() = app.getSystemService(TelephonyManager::class.java)

    private fun voiceSim(operator: String, name: String, carrierId: Int) {
        shadowOf(telephonyManager).apply {
            setSimOperator(operator)
            setSimCarrierIdName(name)
            setSimCarrierId(carrierId)
        }
    }

    private fun sim(operator: String, name: String, carrierId: Int): TelephonyManager {
        val sim = Shadow.newInstanceOf(TelephonyManager::class.java)
        shadowOf(sim).apply {
            setSimOperator(operator)
            setSimCarrierIdName(name)
            setSimCarrierId(carrierId)
        }
        return sim
    }

    @Implements(TelephonyManager::class)
    class UnsupportedTelephonyManager {
        @Implementation
        fun getSimOperator(): String = throw UnsupportedOperationException()
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
            signalStrength = null,
            carrier = null,
            blocked = null
        )
        const val DATA_SUBSCRIPTION = 2
        val WIFI_NETWORK = ShadowNetwork.newInstance(100)
        val CELLULAR_NETWORK = ShadowNetwork.newInstance(101)
    }
}
