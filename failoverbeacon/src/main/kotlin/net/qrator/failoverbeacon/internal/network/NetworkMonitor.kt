package net.qrator.failoverbeacon.internal.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.warn
import net.qrator.failoverbeacon.core.network.NetworkSource
import net.qrator.failoverbeacon.core.network.model.NetworkSnapshot
import net.qrator.failoverbeacon.internal.network.mapper.NetworkSnapshotMapper

internal class NetworkMonitor(context: Context) :
    ConnectivityManager.NetworkCallback(),
    NetworkSource {
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)
    private val snapshotMapper = NetworkSnapshotMapper()

    @Volatile
    private var snapshot: NetworkSnapshot = NetworkSnapshot.Unknown

    override fun currentNetwork(): NetworkSnapshot = snapshot

    fun register() {
        val manager = connectivityManager
        if (manager == null) {
            SdkRegistry.logger.warn { "Cannot watch the network. ConnectivityManager is null." }
            return
        }
        try {
            manager.registerDefaultNetworkCallback(this)
        } catch (e: Exception) {
            SdkRegistry.logger.warn(e) { "Cannot watch the network. Network is unknown." }
        }
    }

    override fun onAvailable(network: Network) {
        // Below API 26 onCapabilitiesChanged does not follow onAvailable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return
        val capabilities = connectivityManager?.getNetworkCapabilities(network) ?: return
        snapshot = snapshotMapper.toSnapshot(capabilities)
    }

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        snapshot = snapshotMapper.toSnapshot(capabilities)
    }

    override fun onLost(network: Network) {
        snapshot = NetworkSnapshot.None
    }
}
