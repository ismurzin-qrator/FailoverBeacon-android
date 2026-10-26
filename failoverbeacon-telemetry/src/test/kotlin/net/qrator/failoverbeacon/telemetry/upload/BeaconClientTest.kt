package net.qrator.failoverbeacon.telemetry.upload

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.SdkRegistry
import net.qrator.failoverbeacon.core.logging.LogOutput
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class BeaconClientTest {
    private val server = MockWebServer()
    private val client = BeaconClient()
    private val previousOutput = SdkRegistry.logger.output
    private val previousLevel = SdkRegistry.logger.minLevel
    private val logs = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        server.start()
        SdkRegistry.logger.minLevel = Log.DEBUG
        SdkRegistry.logger.output = LogOutput { _, message, _ -> logs += message }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        SdkRegistry.logger.output = previousOutput
        SdkRegistry.logger.minLevel = previousLevel
    }

    @Test
    fun `posts the body gzipped as NDJSON and returns the status code`() {
        server.enqueue(MockResponse().setResponseCode(202))

        val code = client.post(
            server.url("/events").toString(),
            "one\ntwo\n".toByteArray()
        )

        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(202, code)
        assertEquals("POST", request.method)
        assertEquals("/events", request.path)
        assertEquals("application/x-ndjson", request.getHeader("Content-Type"))
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        assertEquals(request.bodySize.toString(), request.getHeader("Content-Length"))
        assertEquals("one\ntwo\n", GZIPInputStream(request.body.inputStream()).reader().readText())
        assertTrue(
            logs.contains("[Upload] Body: bytes=8, gzip_bytes=${request.bodySize}")
        )
        assertEquals(1, logs.size)
    }

    @Test
    fun `does not follow a redirect`() {
        server.enqueue(
            MockResponse().setResponseCode(307).setHeader("Location", server.url("/other"))
        )
        server.enqueue(MockResponse().setResponseCode(202))

        val code = client.post(server.url("/").toString(), "one\n".toByteArray())

        assertEquals(307, code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a connection failure is logged without reporting an HTTP response`() {
        val url = server.url("/private?token=secret").toString()
        server.shutdown()

        val code = client.post(url, "one\n".toByteArray())

        assertNull(code)
        assertTrue(logs.any { it.startsWith("[Upload] Cannot send events to ${server.hostName}") })
        assertTrue(logs.none { it.contains("code=") })
        assertTrue(logs.none { it.contains("private") || it.contains("secret") })
    }
}
