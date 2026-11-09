package net.qrator.failoverbeacon.okhttp.internal

import java.net.InetAddress

internal class NetworkAttempt {
    var directHttp2Address: InetAddress? = null
    var avoidedAddress: InetAddress? = null
}
