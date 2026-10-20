package net.qrator.failoverbeacon.core.call

import java.util.Random

internal object CallIdGenerator {
    // Avoid ThreadLocalRandom: broken by Zygote preloading on older Android (bug 234816154).
    private val random = Random()

    fun next(): Long = random.nextLong()
}
