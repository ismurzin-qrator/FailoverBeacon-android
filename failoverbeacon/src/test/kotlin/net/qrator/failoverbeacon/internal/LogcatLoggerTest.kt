package net.qrator.failoverbeacon.internal

import android.util.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class LogcatLoggerTest {
    @Test
    fun `writes each level with the SDK tag`() {
        for (level in Log.VERBOSE..Log.ERROR) {
            LogcatLogger.log(level, "message $level", null)
        }

        val logs = ShadowLog.getLogsForTag("FailoverBeacon")
        assertEquals((Log.VERBOSE..Log.ERROR).toList(), logs.map { it.type })
        assertEquals((Log.VERBOSE..Log.ERROR).map { "message $it" }, logs.map { it.msg })
    }

    @Test
    fun `writes ASSERT as ERROR`() {
        LogcatLogger.log(Log.ASSERT, "message", null)

        assertEquals(Log.ERROR, ShadowLog.getLogsForTag("FailoverBeacon").single().type)
    }

    @Test
    fun `passes the throwable`() {
        val error = IllegalStateException()

        LogcatLogger.log(Log.WARN, "message", error)

        assertSame(error, ShadowLog.getLogsForTag("FailoverBeacon").single().throwable)
    }
}
