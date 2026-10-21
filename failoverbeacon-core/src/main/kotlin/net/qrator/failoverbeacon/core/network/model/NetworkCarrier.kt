package net.qrator.failoverbeacon.core.network.model

/** [networkType] is a `TelephonyManager.NETWORK_TYPE_*` value. */
data class NetworkCarrier(
    val name: String?,
    val mcc: String?,
    val mnc: String?,
    val carrierId: Int?,
    val networkType: Int?
)
