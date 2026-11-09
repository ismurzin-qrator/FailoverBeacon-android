package net.qrator.failoverbeacon.okhttp.internal

import java.io.IOException

internal class SameAddressException : IOException("The retry reached the address that timed out")
