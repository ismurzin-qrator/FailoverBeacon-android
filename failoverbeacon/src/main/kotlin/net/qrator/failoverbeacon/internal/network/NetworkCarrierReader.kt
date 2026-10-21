package net.qrator.failoverbeacon.internal.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.core.network.model.NetworkCarrier

internal class NetworkCarrierReader(private val context: Context) {
    private val telephonyManager: TelephonyManager? =
        context.getSystemService(TelephonyManager::class.java)

    fun read(): NetworkCarrier? {
        val manager = telephonyManager?.let(::dataSimManager) ?: return null
        return try {
            val simOperator = manager.simOperator?.takeIf { it.length in MCC_MNC_LENGTHS }
            NetworkCarrier(
                name = name(manager),
                mcc = simOperator?.take(MCC_LENGTH),
                mnc = simOperator?.drop(MCC_LENGTH),
                carrierId = carrierId(manager),
                networkType = networkType(manager)
            )
        } catch (e: Exception) {
            SdkRegistry.logger.debug(e) { "Cannot read the carrier" }
            null
        }
    }

    private fun dataSimManager(telephonyManager: TelephonyManager): TelephonyManager {
        val subscriptionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SubscriptionManager.getActiveDataSubscriptionId()
        } else {
            SubscriptionManager.getDefaultDataSubscriptionId()
        }
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return telephonyManager
        return telephonyManager.createForSubscriptionId(subscriptionId)
    }

    private fun name(manager: TelephonyManager): String? {
        // Bug: below API 26 the SIM name is the default SIM's, whichever SIM the manager is for.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.simCarrierIdName?.toString()
        } else {
            manager.simOperatorName
        }
        return name?.takeIf { it.isNotEmpty() }
    }

    private fun carrierId(manager: TelephonyManager): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return manager.simCarrierId.takeIf { it != TelephonyManager.UNKNOWN_CARRIER_ID }
    }

    @SuppressLint("MissingPermission")
    private fun networkType(manager: TelephonyManager): Int? {
        val granted = PackageManager.PERMISSION_GRANTED
        val phoneState =
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == granted
        val basicPhoneState = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.READ_BASIC_PHONE_STATE) == granted
        if (!phoneState && !basicPhoneState) return null
        return manager.dataNetworkType.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
    }

    private companion object {
        const val MCC_LENGTH = 3
        val MCC_MNC_LENGTHS = 5..6
    }
}
