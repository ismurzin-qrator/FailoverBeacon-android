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

internal class SavedNetworksStore(private val cacheDir: () -> File) {
    private val serializer = SavedNetworksSerializer()
    private val file by lazy { AtomicFile(File(cacheDir(), FILE_NAME)) }
    private val emptyContent = serializer.toJson(emptyList())
    private var content = emptyContent

    fun read(): List<SavedNetwork> = try {
        val read = file.readFully().toString(Charsets.UTF_8)
        serializer.fromJson(read).also { content = read }
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
        val updated = serializer.toJson(networks)
        if (updated == content) return
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(updated.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            content = updated
        } catch (e: IOException) {
            stream?.let(file::failWrite)
            SdkRegistry.logger.warn(e, onlyOnce = true) { "[Failover] Cannot save host state" }
        }
    }

    fun clear() {
        file.delete()
        content = emptyContent
    }

    private fun logUnreadable(failure: Exception): List<SavedNetwork> {
        SdkRegistry.logger.warn(failure, onlyOnce = true) { "[Failover] Cannot read host state" }
        return emptyList()
    }

    private companion object {
        const val FILE_NAME = "failoverbeacon-failover.json"
    }
}
