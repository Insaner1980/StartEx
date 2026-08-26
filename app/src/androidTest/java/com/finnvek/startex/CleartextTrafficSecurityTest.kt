package com.finnvek.startex

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleartextTrafficSecurityTest {
    @Test
    fun cleartextTrafficIsDeniedForLoopbackHosts() {
        val policy = NetworkSecurityPolicy.getInstance()

        listOf("localhost", "ip6-localhost", "127.0.0.1", "::1").forEach { host ->
            assertFalse("Cleartext traffic is permitted for $host", policy.isCleartextTrafficPermitted(host))
        }
    }
}
