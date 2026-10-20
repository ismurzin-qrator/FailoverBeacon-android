package net.qrator.failoverbeacon.internal

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Application
import android.os.Process
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    private val detector = AndroidMainProcessDetector(app)

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
    @Config(sdk = [27])
    fun `on API 27 the process with the own pid decides`() {
        val other = RunningAppProcessInfo(app.packageName, Process.myPid() + 1, arrayOf())
        val own = RunningAppProcessInfo("${app.packageName}:sync", Process.myPid(), arrayOf())
        shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(listOf(other, own))

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
