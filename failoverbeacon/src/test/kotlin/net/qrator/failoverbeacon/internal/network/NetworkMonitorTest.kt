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
    private val telephonyManager: TelephonyManager = app.getSystemService(
        TelephonyManager::class.java
    )
    private val monitor = NetworkMonitor(app)
    private val previousOutput = SdkRegistry.logger.output

    @AfterTest
    fun tearDown() {
        SdkRegistry.logger.output = previousOutput
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

            assertEquals(expected, connected().copy(carrier = null))
        }
    }

    @Test
    fun `reads the carrier of the SIM that carries mobile data`() {
        sim(telephonyManager, operator = "00101", name = "Carrier A", carrierId = 1)
        val dataSim = sim(operator = "00102", name = "Carrier B", carrierId = 2)
        shadowOf(dataSim).setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
        shadowOf(telephonyManager).setTelephonyManagerForSubscriptionId(DATA_SUBSCRIPTION, dataSim)
        ShadowSubscriptionManager.setActiveDataSubscriptionId(DATA_SUBSCRIPTION)
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)

        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, cellular())

        assertEquals(
            NetworkCarrier("Carrier B", "001", "02", 2, TelephonyManager.NETWORK_TYPE_LTE),
            connected().carrier
        )
    }

    @Test
    @Config(sdk = [29])
    fun `below API 30 the default data SIM is read`() {
        sim(telephonyManager, operator = "00101", name = "Carrier A", carrierId = 1)
        val dataSim = sim(operator = "00102", name = "Carrier B", carrierId = 2)
        shadowOf(telephonyManager).setTelephonyManagerForSubscriptionId(DATA_SUBSCRIPTION, dataSim)
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(DATA_SUBSCRIPTION)

        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, cellular())

        assertEquals("Carrier B", connected().carrier?.name)
    }

    @Test
    @Config(sdk = [27])
    fun `below API 28 the carrier is named by its SIM and has no id`() {
        shadowOf(telephonyManager).apply {
            setSimOperator("00101")
            setSimOperatorName("Carrier A")
        }

        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, cellular())

        assertEquals(
            NetworkCarrier("Carrier A", "001", "01", carrierId = null, networkType = null),
            connected().carrier
        )
    }

    @Test
    @Config(sdk = [25])
    fun `below API 26 the carrier has no name`() {
        shadowOf(telephonyManager).apply {
            setSimOperator("00101")
            setSimOperatorName("Carrier A")
        }

        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, cellular())

        assertEquals(
            NetworkCarrier(name = null, "001", "01", carrierId = null, networkType = null),
            connected().carrier
        )
    }

    @Test
    fun `the network type needs a phone state permission`() {
        sim(telephonyManager, operator = "00101", name = "Carrier A", carrierId = 1)
        shadowOf(telephonyManager).setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE)

        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, cellular())
        val withoutPermission = connected().carrier?.networkType
        shadowOf(app).grantPermissions(Manifest.permission.READ_BASIC_PHONE_STATE)
        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, cellular())

        assertNull(withoutPermission)
        assertEquals(TelephonyManager.NETWORK_TYPE_LTE, connected().carrier?.networkType)
    }

    @Test
    fun `empty and unknown carrier values are not reported`() {
        shadowOf(telephonyManager).apply {
            setSimOperator("")
            setSimCarrierIdName("")
            setSimCarrierId(TelephonyManager.UNKNOWN_CARRIER_ID)
            setDataNetworkType(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        }
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)

        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, cellular())

        assertEquals(NetworkCarrier(null, null, null, null, null), connected().carrier)
    }

    @Test
    fun `there is no carrier off cellular networks`() {
        sim(telephonyManager, operator = "00101", name = "Carrier A", carrierId = 1)

        monitor.onCapabilitiesChanged(WIFI_NETWORK, capabilities(listOf(TRANSPORT_WIFI)))

        assertNull(connected().carrier)
    }

    @Test
    @Config(shadows = [UnsupportedTelephonyManager::class])
    fun `a device without telephony reports the network without a carrier`() {
        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, cellular())

        assertEquals(NetworkTransport.CELLULAR, connected().transport)
        assertNull(connected().carrier)
    }

    @Test
    @Config(sdk = [27])
    fun `roaming is unknown below API 28`() {
        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, cellular())

        assertNull(connected().roaming)
    }

    @Test
    @Config(sdk = [28])
    fun `signal strength is unknown below API 29`() {
        monitor.onCapabilitiesChanged(WIFI_NETWORK, capabilities(signalStrength = -60))

        assertNull(connected().signalStrength)
    }

    @Test
    @Config(sdk = [29])
    fun `signal strength is reported from API 29`() {
        monitor.onCapabilitiesChanged(WIFI_NETWORK, capabilities(signalStrength = -60))

        assertEquals(-60, connected().signalStrength)
    }

    @Test
    @Config(sdk = [24])
    fun `below API 26 a new default network is read when it becomes available`() {
        shadowOf(app.getSystemService(ConnectivityManager::class.java))
            .setNetworkCapabilities(WIFI_NETWORK, capabilities(listOf(TRANSPORT_WIFI)))

        monitor.onAvailable(WIFI_NETWORK)

        assertEquals(NetworkTransport.WIFI, connected().transport)
    }

    @Test
    @Config(sdk = [26])
    fun `from API 26 a new default network waits for its capabilities`() {
        shadowOf(app.getSystemService(ConnectivityManager::class.java))
            .setNetworkCapabilities(WIFI_NETWORK, capabilities(listOf(TRANSPORT_WIFI)))

        monitor.onAvailable(WIFI_NETWORK)

        assertEquals(NetworkSnapshot.Unknown, monitor.currentNetwork())
    }

    @Test
    fun `reports whether the system blocks the network for the app`() {
        monitor.onCapabilitiesChanged(WIFI_NETWORK, capabilities(listOf(TRANSPORT_WIFI)))
        val unreported = connected().blocked
        monitor.onBlockedStatusChanged(WIFI_NETWORK, true)

        assertNull(unreported)
        assertEquals(true, connected().blocked)
    }

    @Test
    fun `blocking survives a capabilities update`() {
        val wifi = capabilities(listOf(TRANSPORT_WIFI))
        monitor.onCapabilitiesChanged(WIFI_NETWORK, wifi)
        monitor.onBlockedStatusChanged(WIFI_NETWORK, true)
        monitor.onCapabilitiesChanged(WIFI_NETWORK, wifi)

        assertEquals(true, connected().blocked)
    }

    @Test
    fun `a new default network does not keep the blocking of the previous one`() {
        monitor.onCapabilitiesChanged(WIFI_NETWORK, capabilities(listOf(TRANSPORT_WIFI)))
        monitor.onBlockedStatusChanged(WIFI_NETWORK, true)
        monitor.onAvailable(CELLULAR_NETWORK)
        monitor.onCapabilitiesChanged(CELLULAR_NETWORK, cellular())

        assertNull(connected().blocked)
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

    @Test
    fun `closing a monitor that never registered is logged`() {
        shadowOf(
            app.getSystemService(ConnectivityManager::class.java)
        ).setStrictUnregistration(true)
        val logged = mutableListOf<Throwable?>()
        SdkRegistry.logger.output = LogOutput { _, _, throwable -> logged += throwable }

        monitor.close()

        assertIs<IllegalArgumentException>(logged.single())
    }

    private fun connected() = assertIs<NetworkSnapshot.Connected>(monitor.currentNetwork())

    private fun cellular() = capabilities(listOf(TRANSPORT_CELLULAR))

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

    private fun sim(
        manager: TelephonyManager = Shadow.newInstanceOf(TelephonyManager::class.java),
        operator: String,
        name: String,
        carrierId: Int
    ): TelephonyManager {
        shadowOf(manager).apply {
            setSimOperator(operator)
            setSimCarrierIdName(name)
            setSimCarrierId(carrierId)
        }
        return manager
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
