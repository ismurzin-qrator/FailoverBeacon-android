package net.qrator.failoverbeacon.failover.model

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.ip

class HostRecordTest {
    private val record = HostRecord(additionalAddresses = listOf(ip(7)))
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
    fun `resolved addresses are fresh until the interval passes`() {
        val resolved = record.withResolved(listOf(ip(1)), nowNanos = 10)

        assertTrue(resolved.isFresh(nowNanos = 9 + MAX_AGE, MAX_AGE))
        assertFalse(resolved.isFresh(nowNanos = 10 + MAX_AGE, MAX_AGE))
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
    fun `connected IPv4 addresses come first by time, then unknown ones, then failed ones`() {
        val ranked = record
            .withResolved(listOf(ip(1), ip(2), ip(3), ip(4), ip(5)), nowNanos = 0)
            .withProbe(ip(1), ProbeResult.Failed)
            .withProbe(ip(3), ProbeResult.Connected(connectNanos = 300))
            .withProbe(ip(4), ProbeResult.Failed)
            .withProbe(ip(5), connected)

        assertEquals(
            listOf(ip(5), ip(3), ip(2), ip(7), ip(1), ip(4)),
            ranked.addresses.keys.toList()
        )
    }

    @Test
    fun `failed addresses keep the source order whatever order they failed in`() {
        val ranked = record
            .withResolved(listOf(ip(1), ip(2)), nowNanos = 0)
            .withProbe(ip(1), ProbeResult.Failed)
            .withProbe(ip(2), ProbeResult.Failed)

        assertEquals(listOf(ip(7), ip(1), ip(2)), ranked.addresses.keys.toList())
    }

    @Test
    fun `IPv6 addresses keep their places`() {
        val ranked = record
            .withResolved(listOf(IPV6, ip(1), OTHER_IPV6, ip(2)), nowNanos = 0)
            .withProbe(ip(1), ProbeResult.Failed)
            .withProbe(ip(2), connected)

        assertEquals(listOf(IPV6, ip(2), OTHER_IPV6, ip(7), ip(1)), ranked.addresses.keys.toList())
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

    @Test
    fun `a resolve failure lasts until an answer with addresses`() {
        val failed = record.withResolved(listOf(ip(1)), nowNanos = 0).withResolveFailure()

        assertTrue(failed.hasResolveFailed)
        assertEquals(listOf(ip(1), ip(7)), failed.addresses.keys.toList())
        assertTrue(failed.withResolved(emptyList(), nowNanos = 1).hasResolveFailed)
        assertFalse(failed.withResolved(listOf(ip(1)), nowNanos = 1).hasResolveFailed)
    }

    @Test
    fun `a resolve round is due before the first one and after the interval`() {
        val started = record.withResolveRound(nowNanos = 10)

        assertTrue(record.isResolveRoundDue(nowNanos = 0, INTERVAL))
        assertFalse(started.isResolveRoundDue(nowNanos = 10 + INTERVAL - 1, INTERVAL))
        assertTrue(started.isResolveRoundDue(nowNanos = 10 + INTERVAL, INTERVAL))
    }

    @Test
    fun `an answer counts as a resolve round and an empty one does not`() {
        val answered = record.withResolved(listOf(ip(1)), nowNanos = 10)
        val empty = record.withResolved(emptyList(), nowNanos = 10)

        assertFalse(answered.isResolveRoundDue(nowNanos = 10, INTERVAL))
        assertTrue(answered.hasAnswer)
        assertTrue(empty.isResolveRoundDue(nowNanos = 10, INTERVAL))
        assertFalse(empty.hasAnswer)
    }

    @Test
    fun `resolve and probe rounds do not affect each other`() {
        val resolving = record.withResolveRound(nowNanos = 10)
        val probing = record.withProbeRound(nowNanos = 10)

        assertTrue(resolving.isProbeRoundDue(nowNanos = 10, INTERVAL))
        assertTrue(probing.isResolveRoundDue(nowNanos = 10, INTERVAL))
    }

    @Test
    fun `a probe round is due before the first one and after the interval`() {
        val started = record.withProbeRound(nowNanos = 10)

        assertTrue(record.isProbeRoundDue(nowNanos = 0, INTERVAL))
        assertFalse(started.isProbeRoundDue(nowNanos = 10 + INTERVAL - 1, INTERVAL))
        assertTrue(started.isProbeRoundDue(nowNanos = 10 + INTERVAL, INTERVAL))
    }

    @Test
    fun `a new answer and a probe result keep the round time and the round keeps the results`() {
        val started = record
            .withResolved(listOf(ip(1)), nowNanos = 0)
            .withProbe(ip(1), connected)
            .withProbeRound(nowNanos = 10)

        val updated = started
            .withResolved(listOf(ip(1), ip(2)), nowNanos = 20)
            .withProbe(ip(2), ProbeResult.Failed)

        assertEquals(connected, started.addresses[ip(1)])
        assertFalse(updated.isProbeRoundDue(nowNanos = 10 + INTERVAL - 1, INTERVAL))
    }

    private companion object {
        const val MAX_AGE = 100L
        const val INTERVAL = 50L
        val IPV6: InetAddress = InetAddress.getByName("2001:db8::1")
        val OTHER_IPV6: InetAddress = InetAddress.getByName("2001:db8::2")
    }
}
