package net.qrator.failoverbeacon.failover.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.failover.fakes.ip

class HostRecordTest {
    private val record = HostRecord(API, additionalAddresses = listOf(ip(7)))

    @Test
    fun `a new record has only the additional addresses and is not fresh`() {
        assertEquals(listOf(ip(7)), record.addresses)
        assertFalse(record.isFresh(nowNanos = 0, MAX_AGE))
    }

    @Test
    fun `resolved addresses come before the additional ones without duplicates`() {
        val resolved = record.withResolved(listOf(ip(1), ip(7)), nowNanos = 0)

        assertEquals(listOf(ip(1), ip(7)), resolved.resolvedAddresses)
        assertEquals(listOf(ip(1), ip(7)), resolved.addresses)
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

        assertEquals(listOf(ip(2), ip(7)), resolved.addresses)
        assertTrue(resolved.isFresh(nowNanos = 150, MAX_AGE))
    }

    @Test
    fun `an empty answer keeps the record`() {
        val resolved = record.withResolved(listOf(ip(1)), nowNanos = 0)

        assertSame(resolved, resolved.withResolved(emptyList(), nowNanos = 50))
    }

    private companion object {
        const val API = "api.example"
        const val MAX_AGE = 100L
    }
}
