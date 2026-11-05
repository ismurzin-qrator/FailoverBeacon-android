package net.qrator.failoverbeacon.core.util

import java.security.MessageDigest

private const val BYTE_MASK = 0xff
private const val HEX = 16
private const val HEX_BYTE_LENGTH = 2

fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") {
        (it.toInt() and BYTE_MASK).toString(HEX).padStart(HEX_BYTE_LENGTH, '0')
    }
