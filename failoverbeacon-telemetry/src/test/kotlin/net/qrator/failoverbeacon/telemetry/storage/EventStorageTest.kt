package net.qrator.failoverbeacon.telemetry.storage

import android.util.Log
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.LogOutput
import net.qrator.failoverbeacon.telemetry.DropReason
import net.qrator.failoverbeacon.telemetry.DroppedEvents
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class EventStorageTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val previousOutput = SdkRegistry.logger.output
    private val logged = mutableListOf<Int>()
    private var now = START
    private val dropped = DroppedEvents(listOf(A, B))
    private val storage = newStorage(A, B)

    @BeforeTest
    fun setUp() {
        SdkRegistry.logger.output = LogOutput { level, _, _ -> logged += level }
    }

    @AfterTest
    fun tearDown() {
        SdkRegistry.logger.output = previousOutput
        SdkRegistry.clear()
    }

    @Test
    fun `writes each line to a batch of every beacon, in a directory named by its URL`() {
        storage.write("one")
        storage.write("two")

        assertEquals(listOf(listOf("one", "two")), batches(A_DIR))
        assertEquals(listOf(listOf("one", "two")), batches(B_DIR))
        assertEquals(setOf(A_DIR, B_DIR), rootDir().list()?.toSet())
    }

    @Test
    fun `a batch takes lines for 95 percent of its window`() {
        storage.write("one")
        now = START + 56_999
        storage.write("two")
        now = START + 57_000
        storage.write("three")

        assertEquals(listOf(START.toString(), (START + 57_000).toString()), names(A_DIR))
        assertEquals(listOf(listOf("one", "two"), listOf("three")), batches(A_DIR))
    }

    @Test
    fun `a full batch starts a new one`() {
        val half = "x".repeat(600 * 1024)

        storage.write(half)
        storage.write(half)

        assertEquals(2, names(A_DIR).size)
    }

    @Test
    fun `a new storage does not add to the batches of an earlier one`() {
        storage.write("earlier")
        newStorage(A, B).write("later")

        assertEquals(listOf(START.toString(), (START + 1).toString()), names(A_DIR))
        assertEquals(listOf(listOf("earlier"), listOf("later")), batches(A_DIR))
    }

    @Test
    fun `deletes the batches of a beacon no longer configured`() {
        storage.write("one")

        newStorage(A).write("two")

        assertEquals(setOf(A_DIR), rootDir().list()?.toSet())
    }

    @Test
    fun `a failed write loses that line for that beacon only and is logged once`() {
        storage.write("one")
        val batch = File(rootDir(), A_DIR).listFiles()!!.single()
        batch.delete()
        batch.mkdir()

        storage.write("two")
        storage.write("three")

        assertEquals(listOf(listOf("three")), batches(A_DIR))
        assertEquals(listOf(listOf("one", "two", "three")), batches(B_DIR))
        assertEquals(listOf(Log.WARN), logged)
    }

    @Test
    fun `writing goes on after the cache directory was cleared`() {
        storage.write("one")
        rootDir().deleteRecursively()

        storage.write("two")
        storage.write("three")

        assertEquals(listOf(listOf("three")), batches(A_DIR))
        assertEquals(listOf(Log.WARN), logged)
    }

    @Test
    fun `a beacon keeps within its share, counting the batch it opens`() {
        val storage = newStorage(A, B, maxBytes = 2_000)

        writeInNewBatches(storage, count = 4, line = "x".repeat(399))

        val written = (0 until 4).map { (START + it * 60_000).toString() }
        assertEquals(written.drop(2), names(A_DIR))
        assertEquals(written.drop(2), names(B_DIR))
        assertTrue(files(A_DIR).sumOf { it.length() } <= 1_000)
        assertEquals(mapOf(DropReason.DISK_OVERFLOW to 2L), dropped.snapshot(A))
        assertEquals(mapOf(DropReason.DISK_OVERFLOW to 2L), dropped.snapshot(B))
    }

    @Test
    fun `one beacon gets the whole limit`() {
        val storage = newStorage(A, maxBytes = 2_000)

        writeInNewBatches(storage, count = 4, line = "x".repeat(399))

        assertEquals(3, names(A_DIR).size)
    }

    @Test
    fun `a batch takes at most half of the share of a beacon`() {
        val storage = newStorage(A, maxBytes = 1_000)

        storage.write("x".repeat(299))
        storage.write("x".repeat(299))

        assertEquals(2, names(A_DIR).size)
    }

    @Test
    fun `drops batches older than 18 hours, named in the future or not by time`() {
        val dir = File(rootDir(), A_DIR).apply { mkdirs() }
        File(dir, (START - EIGHTEEN_HOURS).toString()).writeText("old\n")
        File(dir, (START - EIGHTEEN_HOURS + 1).toString()).writeText("recent\n")
        File(dir, (START + TEN_MINUTES).toString()).writeText("ahead\n")
        File(dir, (START + TEN_MINUTES + 1).toString()).writeText("future\n")
        File(dir, "x").writeText("foreign\n")

        storage.write("one")
        val afterStart = names(A_DIR)
        now = START + EIGHTEEN_HOURS
        storage.write("two")

        assertEquals(
            listOf(START - EIGHTEEN_HOURS + 1, START, START + TEN_MINUTES).map(Long::toString),
            afterStart
        )
        assertEquals(
            listOf(START + TEN_MINUTES, START + EIGHTEEN_HOURS).map(Long::toString),
            names(A_DIR)
        )
        assertEquals(mapOf(DropReason.EXPIRED to 5L), dropped.snapshot(A))
    }

    @Test
    fun `ready batches are the closed ones of a beacon, oldest first`() {
        val beforeWrite = storage.readyBatches(A)
        storage.write("one")
        now = START + 60_000
        storage.write("two")
        now = START + 60_000 + 62_999
        val oneReady = storage.readyBatches(A).map { it.name }
        now += 1

        assertEquals(emptyList(), beforeWrite)
        assertEquals(listOf(START.toString()), oneReady)
        assertEquals(names(A_DIR), storage.readyBatches(A).map { it.name })
    }

    @Test
    fun `batches older than 18 hours, ahead of the clock or not named by time are not ready`() {
        val dir = File(rootDir(), A_DIR).apply { mkdirs() }
        File(dir, (START - EIGHTEEN_HOURS).toString()).writeText("old\n")
        File(dir, (START - EIGHTEEN_HOURS + 1).toString()).writeText("recent\n")
        File(dir, (START + 1).toString()).writeText("ahead\n")
        File(dir, "x").writeText("foreign\n")

        assertEquals(
            listOf((START - EIGHTEEN_HOURS + 1).toString()),
            storage.readyBatches(A).map { it.name }
        )
    }

    @Test
    fun `reading drops a cut last line and a missing batch reads as empty`() {
        val dir = File(rootDir(), A_DIR).apply { mkdirs() }
        val cut = File(dir, "1").apply { writeText("one\ntwo\nthr") }
        val missing = File(dir, "2")

        assertEquals("one\ntwo\n", storage.read(cut).toString(Charsets.UTF_8))
        assertEquals(0, storage.read(missing).size)
    }

    @Test
    fun `a removed beacon loses its batches and gets no more lines`() {
        storage.write("one")
        storage.removeBeacon(A)
        storage.write("two")
        now = START + 120_000

        assertEquals(setOf(B_DIR), rootDir().list()?.toSet())
        assertEquals(listOf(listOf("one", "two")), batches(B_DIR))
        assertEquals(emptyList(), storage.readyBatches(A))
    }

    private fun newStorage(vararg beacons: String, maxBytes: Long = 5L * 1024 * 1024) =
        EventStorage({
            temp.root
        }, beacons.toList(), { now }, batchWindowMillis = 60_000, maxBytes, dropped)

    private fun writeInNewBatches(storage: EventStorage, count: Int, line: String) {
        repeat(count) {
            storage.write(line)
            now += 60_000
        }
    }

    private fun rootDir() = File(temp.root, "failoverbeacon")

    private fun files(dir: String) =
        File(rootDir(), dir).listFiles()!!.filter { it.isFile }.sortedBy { it.name }

    private fun names(dir: String) = files(dir).map { it.name }

    private fun batches(dir: String) = files(dir).map { it.readLines() }

    private companion object {
        const val A = "https://a.example"
        const val B = "https://b.example"
        const val A_DIR = "38612c965a9c4c35"
        const val B_DIR = "35b22c6cbc7988f8"
        const val START = 1_761_040_980_000L
        const val EIGHTEEN_HOURS = 18 * 60 * 60 * 1000L
        const val TEN_MINUTES = 10 * 60 * 1000L
    }
}
