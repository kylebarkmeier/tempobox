package com.tempobox.remoteplay

import android.system.ErrnoException
import android.system.OsConstants
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketException

/** Classifying OS denials (Android 17 local network protection) vs ordinary failures. */
@RunWith(RobolectricTestRunner::class)
class SsdpDiscoveryTest {

    @Test
    fun `EPERM from sendto counts as a permission denial, wrapped or not`() {
        val errno = ErrnoException("sendto", OsConstants.EPERM)
        assertThat(SsdpDiscovery.isPermissionDenial(errno)).isTrue()
        assertThat(SsdpDiscovery.isPermissionDenial(IOException("sendto failed", errno))).isTrue()
    }

    @Test
    fun `SecurityException counts as a permission denial`() {
        assertThat(SsdpDiscovery.isPermissionDenial(SecurityException("denied"))).isTrue()
    }

    @Test
    fun `ordinary network failures are not permission denials`() {
        assertThat(SsdpDiscovery.isPermissionDenial(SocketException("Network is unreachable")))
            .isFalse()
        assertThat(
            SsdpDiscovery.isPermissionDenial(
                IOException("sendto failed", ErrnoException("sendto", OsConstants.ENETUNREACH)),
            ),
        ).isFalse()
    }
}
