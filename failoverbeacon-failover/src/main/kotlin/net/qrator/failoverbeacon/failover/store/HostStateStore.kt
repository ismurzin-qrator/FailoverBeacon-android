package net.qrator.failoverbeacon.failover.store

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.warn
import net.qrator.failoverbeacon.failover.model.SavedNetwork
import net.qrator.failoverbeacon.failover.serialization.SavedNetworksSerializer
import org.json.JSONException

internal class HostStateStore(private val cacheDir: () -> File) {
    private val serializer = SavedNetworksSerializer()
    private val file by lazy { AtomicFile(File(cacheDir(), FILE_NAME)) }

    fun read(): List<SavedNetwork> = try {
        serializer.fromJson(file.readFully().toString(Charsets.UTF_8))
    } catch (_: FileNotFoundException) {
        emptyList()
    } catch (e: IOException) {
        logUnreadable(e)
    } catch (e: JSONException) {
        logUnreadable(e)
    } catch (e: NumberFormatException) {
        logUnreadable(e)
    }

    fun write(networks: List<SavedNetwork>) {
        var stream: FileOutputStream? = null
        try {
            val bytes = serializer.toJson(networks).toByteArray(Charsets.UTF_8)
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: IOException) {
            stream?.let(file::failWrite)
            SdkRegistry.logger.warn(e, onlyOnce = true) { "[Failover] Cannot save host state" }
        }
    }

    fun clear() = file.delete()

    private fun logUnreadable(failure: Exception): List<SavedNetwork> {
        SdkRegistry.logger.warn(failure, onlyOnce = true) { "[Failover] Cannot read host state" }
        return emptyList()
    }

    private companion object {
        const val FILE_NAME = "failoverbeacon-failover.json"
    }
}
