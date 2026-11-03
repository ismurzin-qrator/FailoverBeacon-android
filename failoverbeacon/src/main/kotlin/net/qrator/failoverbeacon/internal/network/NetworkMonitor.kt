package net.qrator.failoverbeacon.internal.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.util.concurrent.atomic.AtomicReference
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.warn
import net.qrator.failoverbeacon.core.network.NetworkSource
import net.qrator.failoverbeacon.core.network.model.DefaultNetwork
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot
import net.qrator.failoverbeacon.internal.network.mapper.NetworkKeyMapper
import net.qrator.failoverbeacon.internal.network.mapper.NetworkSnapshotMapper
import net.qrator.failoverbeacon.internal.network.state.NetworkState

internal class NetworkMonitor(context: Context) :
    ConnectivityManager.NetworkCallback(),
    NetworkSource {
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)
    private val carrierReader = NetworkCarrierReader(context)
    private val snapshotMapper = NetworkSnapshotMapper()
    private val keyMapper = NetworkKeyMapper()

    private val snapshot = AtomicReference<NetworkSnapshot>(NetworkSnapshot.Unknown)
    private var state = NetworkState()
    private var onNetworkChanged: (DefaultNetwork?) -> Unit = {}
    private var reported: DefaultNetwork? = null

    override fun currentNetwork(): NetworkSnapshot = snapshot.get()

    fun register(onNetworkChanged: (DefaultNetwork?) -> Unit) {
        this.onNetworkChanged = onNetworkChanged
        val manager = connectivityManager
        if (manager == null) {
            SdkRegistry.logger.warn {
                "[Network] Cannot watch the network. ConnectivityManager is null"
            }
            return
        }
        try {
            val activeNetwork = manager.activeNetwork
            if (activeNetwork != null) {
                manager.getNetworkCapabilities(activeNetwork)?.let {
                    onCapabilitiesChanged(activeNetwork, it)
                }
                manager.getLinkProperties(activeNetwork)?.let {
                    onLinkPropertiesChanged(activeNetwork, it)
                }
                // getActiveNetwork returns null for a blocked network.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    onBlockedStatusChanged(activeNetwork, false)
                }
            }
            manager.registerDefaultNetworkCallback(this)
            if (activeNetwork == null) {
                snapshot.compareAndSet(NetworkSnapshot.Unknown, NetworkSnapshot.None)
            }
        } catch (e: Exception) {
            SdkRegistry.logger.warn(e) { "[Network] Cannot watch the network. Network is unknown" }
            state = NetworkState()
            snapshot.set(NetworkSnapshot.Unknown)
            report(null)
        }
    }

    override fun onAvailable(network: Network) {
        state = NetworkState()
        // Below API 26 onCapabilitiesChanged and onLinkPropertiesChanged do not follow onAvailable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return
        val manager = connectivityManager ?: return
        val capabilities = manager.getNetworkCapabilities(network) ?: return
        onCapabilitiesChanged(network, capabilities)
        val linkProperties = manager.getLinkProperties(network) ?: return
        onLinkPropertiesChanged(network, linkProperties)
    }

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        state.capabilities = capabilities
        val cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        state.carrier = if (cellular) carrierReader.read() else null
        snapshot.set(snapshotMapper.toSnapshot(network.networkHandle, state))
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        state.linkProperties = linkProperties
        report(network)
    }

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
        state.blocked = blocked
        snapshot.set(snapshotMapper.toSnapshot(network.networkHandle, state))
        report(network)
    }

    override fun onLost(network: Network) {
        state = NetworkState()
        snapshot.set(NetworkSnapshot.None)
        report(null)
    }

    override fun close() {
        try {
            connectivityManager?.unregisterNetworkCallback(this)
        } catch (e: Exception) {
            SdkRegistry.logger.warn(e) { "[Network] Cannot stop watching the network" }
        }
    }

    private fun report(network: Network?) {
        val defaultNetwork = if (network == null) {
            null
        } else {
            val capabilities = state.capabilities ?: return
            val linkProperties = state.linkProperties ?: return
            // From API 29 the blocked status of a new network follows its link properties.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && state.blocked == null) return
            val key = keyMapper.toKey(capabilities, linkProperties, state.carrier)
            DefaultNetwork(network.networkHandle, key, state.blocked)
        }
        if (defaultNetwork == reported) return
        reported = defaultNetwork
        onNetworkChanged(defaultNetwork)
    }
}
