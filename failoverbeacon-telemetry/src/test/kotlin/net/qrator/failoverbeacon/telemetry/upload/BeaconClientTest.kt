package net.qrator.failoverbeacon.telemetry.upload

import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class BeaconClientTest {
    private val server = MockWebServer()
    private val client = BeaconClient()

    @BeforeTest
    fun setUp() {
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `posts the body gzipped as NDJSON and returns the status code`() {
        server.enqueue(MockResponse().setResponseCode(202))

        val code = client.post(server.url("/events").toString(), "one\ntwo\n".toByteArray())

        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(202, code)
        assertEquals("POST", request.method)
        assertEquals("/events", request.path)
        assertEquals("application/x-ndjson", request.getHeader("Content-Type"))
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        assertEquals(request.bodySize.toString(), request.getHeader("Content-Length"))
        assertEquals("one\ntwo\n", GZIPInputStream(request.body.inputStream()).reader().readText())
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
}
