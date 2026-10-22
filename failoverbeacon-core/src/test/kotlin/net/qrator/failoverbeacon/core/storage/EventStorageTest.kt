package net.qrator.failoverbeacon.core.storage

import android.util.Log
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.LogOutput
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class EventStorageTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val previousOutput = SdkRegistry.logger.output
    private val logged = mutableListOf<Int>()
    private var now = START
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
    fun `a batch takes lines for 0,95 of its window`() {
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
    fun `a failed write drops the line and is logged once`() {
        storage.write("one")
        val batch = File(rootDir(), A_DIR).listFiles()!!.single()
        batch.setWritable(false)

        storage.write("two")
        storage.write("three")
        batch.setWritable(true)

        assertEquals(listOf(listOf("one")), batches(A_DIR))
        assertEquals(listOf(listOf("one", "two", "three")), batches(B_DIR))
        assertEquals(listOf(Log.WARN), logged)
    }

    @Test
    fun `a full share drops the oldest batches of that beacon`() {
        val storage = newStorage(A, B, maxBytes = 2_000)

        writeInNewBatches(storage, count = 4, line = "x".repeat(399))

        val written = (0 until 4).map { (START + it * 60_000).toString() }
        assertEquals(written.drop(1), names(A_DIR))
        assertEquals(written.drop(1), names(B_DIR))
    }

    @Test
    fun `one beacon gets the whole limit`() {
        val storage = newStorage(A, maxBytes = 2_000)

        writeInNewBatches(storage, count = 4, line = "x".repeat(399))

        assertEquals(4, names(A_DIR).size)
    }

    @Test
    fun `a batch is no larger than the share of a beacon`() {
        val storage = newStorage(A, maxBytes = 1_000)

        storage.write("x".repeat(599))
        storage.write("x".repeat(599))

        assertEquals(2, names(A_DIR).size)
    }

    @Test
    fun `drops batches older than 18 hours, including those of an earlier storage`() {
        val dir = File(rootDir(), A_DIR).apply { mkdirs() }
        File(dir, (START - EIGHTEEN_HOURS).toString()).writeText("old\n")
        File(dir, (START - EIGHTEEN_HOURS + 1).toString()).writeText("recent\n")

        storage.write("one")
        now = START + 1
        val afterStart = names(A_DIR)
        now = START + EIGHTEEN_HOURS
        storage.write("two")

        assertEquals(listOf(START - EIGHTEEN_HOURS + 1, START).map(Long::toString), afterStart)
        assertEquals(listOf(START + EIGHTEEN_HOURS).map(Long::toString), names(A_DIR))
    }

    private fun newStorage(vararg beacons: String, maxBytes: Long = 5L * 1024 * 1024) =
        EventStorage({ temp.root }, beacons.toList(), { now }, batchWindowMillis = 60_000, maxBytes)

    private fun writeInNewBatches(storage: EventStorage, count: Int, line: String) {
        repeat(count) {
            storage.write(line)
            now += 60_000
        }
    }

    private fun rootDir() = File(temp.root, "failoverbeacon")

    private fun files(dir: String) = File(rootDir(), dir).listFiles()!!.sortedBy { it.name }

    private fun names(dir: String) = files(dir).map { it.name }

    private fun batches(dir: String) = files(dir).map { it.readLines() }

    private companion object {
        const val A = "https://a.example"
        const val B = "https://b.example"
        const val A_DIR = "38612c965a9c4c35"
        const val B_DIR = "35b22c6cbc7988f8"
        const val START = 1_761_040_980_000L
        const val EIGHTEEN_HOURS = 18 * 60 * 60 * 1000L
    }
}
