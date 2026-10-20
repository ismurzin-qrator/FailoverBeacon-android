package net.qrator.failoverbeacon

import android.util.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class FailoverBeaconDefaultsTest {
    @Test
    fun `logs warnings to Logcat by default`() {
        FailoverBeacon.setConsent(Consent.GRANTED)

        val log = ShadowLog.getLogsForTag("FailoverBeacon").single()
        assertEquals(Log.WARN, log.type)
        assertEquals("SDK is not running. setConsent ignored", log.msg)
    }
}
