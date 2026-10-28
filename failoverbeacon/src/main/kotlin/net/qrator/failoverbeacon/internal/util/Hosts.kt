package net.qrator.failoverbeacon.internal.util

import java.net.IDN
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

private const val FORBIDDEN_DOMAIN_CHARS = "#%/:<>?@[\\]^|"

/** Returns [host] as a lowercase ASCII domain name, or null if it is not a valid one. */
internal fun canonicalHost(host: String): String? {
    val ascii = try {
        IDN.toASCII(host).lowercase(Locale.ROOT)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val valid = ascii.length <= 253 &&
        ascii.none { it <= ' ' || it >= '\u007f' || it in FORBIDDEN_DOMAIN_CHARS } &&
        ascii.split('.').all { it.length in 1..63 }
    return if (valid) ascii else null
}

/** Returns true if URL parsers read [host] as an IPv4 address, like `192.0.2.10` or `0x7f.1`. */
internal fun endsInNumber(host: String): Boolean {
    val last = host.substringAfterLast('.')
    if (last.isEmpty()) return false
    if (last.all { it in '0'..'9' }) return true
    return last.startsWith("0x") && last.drop(2).all { it in '0'..'9' || it in 'a'..'f' }
}

/** Parses an address like `192.0.2.10` without DNS and names it [host], or returns null. */
internal fun parseIpv4(address: String, host: String): Inet4Address? {
    val octets = address.split('.')
    if (octets.size != 4 || !octets.all(::isOctet)) return null
    val bytes = ByteArray(4) { octets[it].toInt().toByte() }
    return InetAddress.getByAddress(host, bytes) as Inet4Address
}

private fun isOctet(octet: String): Boolean = octet.length in 1..3 &&
    octet.all { it in '0'..'9' } &&
    (octet.length == 1 || octet[0] != '0') &&
    octet.toInt() <= 255
