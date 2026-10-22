package net.qrator.failoverbeacon.core.storage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug
import net.qrator.failoverbeacon.core.logging.warn
import net.qrator.failoverbeacon.core.time.EpochClock

class EventStorage(
    private val cacheDir: () -> File,
    beacons: List<String>,
    private val clock: EpochClock,
    batchWindowMillis: Long,
    maxBytes: Long
) {
    private val writeWindowMillis = batchWindowMillis * WRITE_PERCENT / 100
    private val beaconDirNames = beacons.associateWith(::dirName)
    private val beaconMaxBytes = maxBytes / beaconDirNames.size.coerceAtLeast(1)
    private val maxBatchBytes = minOf(MAX_BATCH_BYTES, beaconMaxBytes)
    private val rootDir by lazy { File(cacheDir(), ROOT_DIR_NAME).also(::deleteRemovedBeacons) }
    private val openBatches = HashMap<String, OpenBatch>()

    fun write(line: String) {
        val bytes = (line + '\n').toByteArray(Charsets.UTF_8)
        val now = clock.nowMillis()
        for (beacon in beaconDirNames.keys) {
            try {
                append(beacon, bytes, now)
            } catch (e: IOException) {
                SdkRegistry.logger.warn(e, onlyOnce = true) {
                    "An event could not be written to disk."
                }
            }
        }
    }

    fun clear() {
        openBatches.clear()
        rootDir.deleteRecursively()
    }

    private fun append(beacon: String, bytes: ByteArray, now: Long) {
        val batch = openBatches[beacon]?.takeIf { it.accepts(bytes.size, now) }
            ?: OpenBatch(newBatchFile(beaconDir(beacon), now)).also { openBatches[beacon] = it }
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

    private fun newBatchFile(dir: File, now: Long): File {
        dropOldBatches(dir, now)
        dir.mkdirs()
        var name = now
        while (File(dir, name.toString()).exists()) name++
        return File(dir, name.toString())
    }

    private fun dropOldBatches(dir: File, now: Long) {
        val batches = dir.listFiles()?.sortedBy { it.name.toLongOrNull() ?: 0 } ?: return
        var size = batches.sumOf { it.length() }
        for (batch in batches) {
            val expired = batch.name.toLongOrNull()?.let { now - it >= MAX_AGE_MILLIS } ?: true
            if (!expired && size <= beaconMaxBytes) break
            size -= batch.length()
            batch.delete()
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
        const val WRITE_PERCENT = 95
        const val MAX_BATCH_BYTES = 1024L * 1024
        const val MAX_AGE_MILLIS = 18 * 60 * 60 * 1000L
        const val DIR_NAME_LENGTH = 16
        const val BYTE_MASK = 0xff
        const val HEX = 16

        fun dirName(beacon: String): String = MessageDigest.getInstance("SHA-256")
            .digest(beacon.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX).padStart(2, '0') }
            .take(DIR_NAME_LENGTH)
    }
}
