package net.qrator.failoverbeacon.telemetry.upload

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.debug

internal class BeaconClient {
    fun post(url: String, body: ByteArray): Int? {
        val gzipped = gzip(body)
        var connection: HttpURLConnection? = null
        val url = URL(url)
        SdkRegistry.logger.debug {
            "[Upload] Body: bytes=${body.size}, gzip_bytes=${gzipped.size}"
        }
        return try {
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(gzipped.size)
            connection.setRequestProperty("Content-Type", "application/x-ndjson")
            connection.setRequestProperty("Content-Encoding", "gzip")
            connection.outputStream.use { it.write(gzipped) }
            connection.responseCode.also { connection.closeResponse(it) }
        } catch (e: IOException) {
            SdkRegistry.logger.debug(e) { "[Upload] Cannot send events to ${url.host}" }
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun gzip(body: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(body) }
        return bytes.toByteArray()
    }

    private fun HttpURLConnection.closeResponse(code: Int) {
        try {
            (if (code < HTTP_BAD_REQUEST) inputStream else errorStream)?.close()
        } catch (e: IOException) {
            SdkRegistry.logger.debug(e) { "[Upload] Cannot close the response of ${url.host}" }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val HTTP_BAD_REQUEST = 400
    }
}
