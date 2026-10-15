package net.qrator.failoverbeacon.core.call

import java.util.Random

object CallIdGenerator {
    // Not ThreadLocalRandom: on most Android versions it starts from the same seed every time.
    private val random = Random()

    fun next(): Long = random.nextLong()
}
