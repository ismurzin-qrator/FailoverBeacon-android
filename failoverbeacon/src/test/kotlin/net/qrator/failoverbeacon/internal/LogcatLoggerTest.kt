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
    fun `writes each level with the SDK tag and the throwable, ASSERT as ERROR`() {
        val error = IllegalStateException()

        for (level in Log.VERBOSE..Log.ASSERT) {
            LogcatLogger.log(level, "message $level", error)
        }

        val logs = ShadowLog.getLogsForTag("FailoverBeacon")
        assertEquals((Log.VERBOSE..Log.ERROR) + Log.ERROR, logs.map { it.type })
        assertEquals((Log.VERBOSE..Log.ASSERT).map { "message $it" }, logs.map { it.msg })
        logs.forEach { assertSame(error, it.throwable) }
    }
}
