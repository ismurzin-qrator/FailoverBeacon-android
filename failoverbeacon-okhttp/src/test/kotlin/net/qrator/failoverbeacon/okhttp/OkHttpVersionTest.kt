package net.qrator.failoverbeacon.okhttp

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

class OkHttpVersionTest {
    @Test
    fun `runs on the OkHttp version the test task expects`() {
        val expected = System.getProperty("failoverbeacon.okhttpVersion")
        assumeTrue(expected != null)

        assertEquals(expected, loadedOkHttpVersion())
    }

    private fun loadedOkHttpVersion(): String = try {
        Class.forName("okhttp3.OkHttp").getField("VERSION").get(null) as String
    } catch (_: ClassNotFoundException) {
        val userAgent = Class.forName("okhttp3.internal.Version").getMethod("userAgent")
        (userAgent.invoke(null) as String).removePrefix("okhttp/")
    }
}
