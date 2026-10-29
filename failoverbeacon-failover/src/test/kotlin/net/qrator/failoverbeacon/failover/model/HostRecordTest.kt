package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.ip

class HostRecordTest {
    private val record = HostRecord(API, additionalAddresses = listOf(ip(7)))
    private val connected = ProbeResult.Connected(connectNanos = 40)

    @Test
    fun `a new record has the additional addresses unprobed and is not fresh`() {
        assertEquals(mapOf(ip(7) to ProbeResult.Unknown), record.addresses)
        assertFalse(record.isFresh(nowNanos = 0, MAX_AGE))
    }

    @Test
    fun `resolved addresses come before the additional ones without duplicates`() {
        val resolved = record.withResolved(listOf(ip(1), ip(7), IPV6), nowNanos = 0)

        assertEquals(listOf(ip(1), ip(7), IPV6), resolved.addresses.keys.toList())
    }

    @Test
    fun `resolved addresses are fresh up to the max age`() {
        val resolved = record.withResolved(listOf(ip(1)), nowNanos = 10)

        assertTrue(resolved.isFresh(nowNanos = 10 + MAX_AGE, MAX_AGE))
        assertFalse(resolved.isFresh(nowNanos = 11 + MAX_AGE, MAX_AGE))
    }

    @Test
    fun `a new answer replaces the resolved addresses and their age`() {
        val resolved = record
            .withResolved(listOf(ip(1)), nowNanos = 0)
            .withResolved(listOf(ip(2)), nowNanos = 90)

        assertEquals(listOf(ip(2), ip(7)), resolved.addresses.keys.toList())
        assertTrue(resolved.isFresh(nowNanos = 150, MAX_AGE))
    }

    @Test
    fun `an empty answer keeps the record`() {
        val resolved = record.withResolved(listOf(ip(1)), nowNanos = 0)

        assertSame(resolved, resolved.withResolved(emptyList(), nowNanos = 50))
    }

    @Test
    fun `a probe result is kept by its address in place`() {
        val probed = record
            .withResolved(listOf(ip(1), ip(2)), nowNanos = 0)
            .withProbe(ip(2), connected)

        assertEquals(
            mapOf(ip(1) to ProbeResult.Unknown, ip(2) to connected, ip(7) to ProbeResult.Unknown),
            probed.addresses
        )
        assertEquals(listOf(ip(1), ip(2), ip(7)), probed.addresses.keys.toList())
    }

    @Test
    fun `a probe result of an address the host no longer has is dropped`() {
        val resolved = record.withResolved(listOf(ip(1)), nowNanos = 0)

        assertSame(resolved, resolved.withProbe(ip(2), ProbeResult.Failed))
    }

    @Test
    fun `a new answer keeps the results of the addresses it still has`() {
        val resolved = record
            .withResolved(listOf(ip(1), ip(2)), nowNanos = 0)
            .withProbe(ip(1), connected)
            .withProbe(ip(2), ProbeResult.Failed)
            .withProbe(ip(7), ProbeResult.Failed)
            .withResolved(listOf(ip(1), ip(3)), nowNanos = 50)

        assertEquals(
            mapOf(ip(1) to connected, ip(3) to ProbeResult.Unknown, ip(7) to ProbeResult.Failed),
            resolved.addresses
        )
    }

    private companion object {
        const val API = "api.example"
        const val MAX_AGE = 100L
        val IPV6: InetAddress = InetAddress.getByName("2001:db8::1")
    }
}
