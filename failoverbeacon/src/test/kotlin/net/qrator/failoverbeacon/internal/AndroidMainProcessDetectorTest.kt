package net.qrator.failoverbeacon.internal

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Application
import android.os.Process
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.qrator.failoverbeacon.core.logging.InternalLogger
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
class AndroidMainProcessDetectorTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val detector = AndroidMainProcessDetector(app, InternalLogger())

    @Test
    @Config(sdk = [28])
    fun `main process on API 28`() {
        ShadowApplication.setProcessName(app.packageName)

        assertTrue(detector.isMainProcess())
    }

    @Test
    @Config(sdk = [28])
    fun `secondary process on API 28`() {
        ShadowApplication.setProcessName("${app.packageName}:sync")

        assertFalse(detector.isMainProcess())
    }

    @Test
    @Config(sdk = [28])
    fun `main process renamed with android process attribute`() {
        app.applicationInfo.processName = "${app.packageName}.main"
        ShadowApplication.setProcessName("${app.packageName}.main")

        assertTrue(detector.isMainProcess())
    }

    @Test
    @Config(sdk = [28])
    fun `unknown process name counts as main`() {
        ShadowApplication.setProcessName(null)

        assertTrue(detector.isMainProcess())
    }

    @Test
    @Config(sdk = [28])
    fun `isolated process on API 28`() {
        ShadowApplication.setProcessName(app.packageName)
        ShadowProcess.setUid(app.applicationInfo.uid + 1)

        assertFalse(detector.isMainProcess())
    }

    @Test
    @Config(sdk = [27])
    fun `main process on API 27`() {
        setRunningProcess(app.packageName)

        assertTrue(detector.isMainProcess())
    }

    @Test
    @Config(sdk = [27])
    fun `secondary process on API 27`() {
        setRunningProcess("${app.packageName}:sync")

        assertFalse(detector.isMainProcess())
    }

    @Test
    @Config(sdk = [27])
    fun `own process missing from running processes counts as main`() {
        shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(emptyList())

        assertTrue(detector.isMainProcess())
    }

    @Test
    @Config(sdk = [27])
    fun `isolated process on API 27`() {
        ShadowProcess.setUid(app.applicationInfo.uid + 1)

        assertFalse(detector.isMainProcess())
    }

    private fun setRunningProcess(name: String) {
        val process = RunningAppProcessInfo(name, Process.myPid(), arrayOf(app.packageName))
        shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(listOf(process))
    }
}
