package net.qrator.failoverbeacon.okhttp.internal.mapper

import android.system.ErrnoException
import android.system.OsConstants
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import net.qrator.failoverbeacon.core.call.model.FailureType
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36])
class OkHttpFailureMapperErrnoTest {
    @Test
    fun `maps errno as Chromium does`() {
        val expected = mapOf(
            OsConstants.ETIMEDOUT to FailureType.TIMED_OUT,
            OsConstants.ECONNRESET to FailureType.RESET,
            OsConstants.ENETRESET to FailureType.RESET,
            OsConstants.EPIPE to FailureType.RESET,
            OsConstants.ECONNABORTED to FailureType.ABORTED,
            OsConstants.ECONNREFUSED to FailureType.REFUSED,
            OsConstants.EHOSTUNREACH to FailureType.ADDRESS_UNREACHABLE,
            OsConstants.ENETUNREACH to FailureType.ADDRESS_UNREACHABLE,
            OsConstants.EAFNOSUPPORT to FailureType.ADDRESS_UNREACHABLE,
            OsConstants.EADDRNOTAVAIL to FailureType.ADDRESS_INVALID
        )

        val actual = expected.mapValues { (errno, _) ->
            OkHttpFailureMapper().toFailureType(ErrnoException("connect", errno))
        }

        assertEquals(expected, actual)
    }
}
