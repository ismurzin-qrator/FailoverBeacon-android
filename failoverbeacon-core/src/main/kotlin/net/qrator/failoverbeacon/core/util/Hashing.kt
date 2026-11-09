package net.qrator.failoverbeacon.core.util

import java.security.MessageDigest

fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()
