package net.qrator.failoverbeacon.core.util

private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16
private const val HEX_BYTE_LENGTH = 2

fun ByteArray.toHex(): String = joinToString("") {
    (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_BYTE_LENGTH, '0')
}
