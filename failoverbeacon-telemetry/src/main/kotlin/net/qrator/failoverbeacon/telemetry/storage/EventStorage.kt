package net.qrator.failoverbeacon.telemetry.storage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.core.logging.warn
import net.qrator.failoverbeacon.core.time.EpochClock
import net.qrator.failoverbeacon.telemetry.DropReason
import net.qrator.failoverbeacon.telemetry.DroppedEvents

internal class EventStorage(
    private val cacheDir: () -> File,
    beacons: List<String>,
    private val clock: EpochClock,
    batchWindowMillis: Long,
    maxBytes: Long,
    private val dropped: DroppedEvents
) {
    private val writeWindowMillis = batchWindowMillis / 100 * WRITE_PERCENT
    private val readWindowMillis = batchWindowMillis / 100 * READ_PERCENT
    private val beaconDirNames by lazy { beacons.associateWith(::dirName) }
    private val beaconMaxBytes = maxBytes / beacons.size.coerceAtLeast(1)
    private val maxBatchBytes = minOf(MAX_BATCH_BYTES, beaconMaxBytes / 2)
    private val rootDir by lazy { File(cacheDir(), ROOT_DIR_NAME).also(::deleteRemovedBeacons) }
    private val openBatches = HashMap<String, OpenBatch>()
    private val writtenBeacons = beacons.toMutableSet()

    fun write(line: String) {
        val bytes = (line + '\n').toByteArray(Charsets.UTF_8)
        val now = clock.nowMillis()
        for (beacon in writtenBeacons) {
            try {
                append(beacon, bytes, now)
            } catch (e: Exception) {
                openBatches.remove(beacon)
                SdkRegistry.logger.warn(e, onlyOnce = true) {
                    "Cannot write an event to disk"
                }
            }
        }
    }

    fun readyBatches(beacon: String): List<File> {
        val now = clock.nowMillis()
        return beaconDir(beacon).listFiles().orEmpty()
            .mapNotNull { file ->
                val created = file.name.toLongOrNull() ?: return@mapNotNull null
                file.takeIf { now - created in readWindowMillis until MAX_AGE_MILLIS }
            }
            .sortedBy { it.name.toLongOrNull() }
    }

    fun read(batch: File): ByteArray {
        val bytes = try {
            batch.readBytes()
        } catch (e: IOException) {
            SdkRegistry.logger.debug(e) { "Cannot read stored events" }
            return ByteArray(0)
        }
        return bytes.copyOf(bytes.lastIndexOf(NEW_LINE) + 1)
    }

    fun delete(batches: List<File>) {
        batches.forEach(File::delete)
    }

    fun removeBeacon(beacon: String) {
        writtenBeacons -= beacon
        openBatches.remove(beacon)
        beaconDir(beacon).deleteRecursively()
    }

    fun clear() {
        openBatches.clear()
        if (!rootDir.deleteRecursively()) {
            SdkRegistry.logger.warn { "Cannot delete the stored events" }
        }
    }

    private fun append(beacon: String, bytes: ByteArray, now: Long) {
        val batch = openBatches[beacon]?.takeIf { it.accepts(bytes.size, now) }
            ?: OpenBatch(newBatchFile(beacon, now)).also { openBatches[beacon] = it }
        RandomAccessFile(batch.file, "rw").use { file ->
            val length = file.length()
            try {
                file.seek(length)
                file.write(bytes)
            } catch (e: IOException) {
                file.setLength(length)
                throw e
            }
        }
        batch.size += bytes.size
    }

    private fun newBatchFile(beacon: String, now: Long): File {
        val dir = beaconDir(beacon)
        dropOldBatches(beacon, dir, now)
        dir.mkdirs()
        var name = now
        while (File(dir, name.toString()).exists()) name++
        return File(dir, name.toString())
    }

    private fun dropOldBatches(beacon: String, dir: File, now: Long) {
        val batches = dir.listFiles()?.sortedBy { it.name.toLongOrNull() ?: 0 } ?: return
        var size = batches.sumOf { it.length() }
        for (batch in batches) {
            val created = batch.name.toLongOrNull()
            val expired = created == null ||
                now - created >= MAX_AGE_MILLIS ||
                created - now > CLOCK_TOLERANCE_MILLIS
            if (!expired && size + maxBatchBytes <= beaconMaxBytes) continue
            size -= batch.length()
            val lines = read(batch).count { it == NEW_LINE }.toLong()
            batch.delete()
            dropped.add(
                beacon,
                if (expired) DropReason.EXPIRED else DropReason.DISK_OVERFLOW,
                lines
            )
            SdkRegistry.logger.debug {
                if (expired) "Expired events dropped" else "Disk queue is full. Old events dropped"
            }
        }
    }

    private fun beaconDir(beacon: String) = File(rootDir, beaconDirNames.getValue(beacon))

    private fun deleteRemovedBeacons(root: File) {
        val kept = beaconDirNames.values.toSet()
        root.listFiles()?.filter { it.name !in kept }?.forEach { it.deleteRecursively() }
    }

    private inner class OpenBatch(val file: File) {
        private val createdMillis = file.name.toLong()
        var size = 0L

        fun accepts(bytes: Int, now: Long): Boolean =
            now - createdMillis < writeWindowMillis && size + bytes <= maxBatchBytes
    }

    private companion object {
        const val ROOT_DIR_NAME = "failoverbeacon"
        const val NEW_LINE = '\n'.code.toByte()
        const val WRITE_PERCENT = 95
        const val READ_PERCENT = 105
        const val MAX_BATCH_BYTES = 1024L * 1024
        const val MAX_AGE_MILLIS = 18 * 60 * 60 * 1000L
        const val CLOCK_TOLERANCE_MILLIS = 10 * 60 * 1000L
        const val DIR_NAME_LENGTH = 16
        const val BYTE_MASK = 0xff
        const val HEX = 16

        fun dirName(beacon: String): String = MessageDigest.getInstance("SHA-256")
            .digest(beacon.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX).padStart(2, '0') }
            .take(DIR_NAME_LENGTH)
    }
}
