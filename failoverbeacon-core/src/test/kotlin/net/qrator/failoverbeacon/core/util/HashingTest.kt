package net.qrator.failoverbeacon.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class HashingTest {
    @Test
    fun `sha256Hex matches the standard test vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc")
        )
    }
}
