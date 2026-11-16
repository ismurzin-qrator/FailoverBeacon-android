package net.qrator.failoverbeacon.telemetry.upload

import android.app.Application
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 37])
class AppVersionTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `reads the app's versionName`() {
        setVersionName("3.14.0")

        assertEquals("3.14.0", app.appVersionName())
    }

    @Test
    fun `an app without a versionName has none`() {
        setVersionName(null)
        assertNull(app.appVersionName())

        setVersionName("")
        assertNull(app.appVersionName())
    }

    private fun setVersionName(name: String?) {
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).versionName =
            name
    }
}
