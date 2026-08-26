package com.finnvek.startex.ui

import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class BiometricApiCompatibilityTest {
    @Test
    @Config(sdk = [29])
    fun `Android 10 non-crypto prompt uses a supported authenticator set`() {
        val prompt =
            authenticationPromptInfo(
                RuntimeEnvironment.getApplication(),
                StartExUiEvent.Authenticate(AuthenticationPurpose.UnlockTrustedAddress),
            )

        assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG, prompt.allowedAuthenticators)
    }

    @Test
    @Config(sdk = [30])
    fun `Android 11 non-crypto prompt retains credential fallback`() {
        val prompt =
            authenticationPromptInfo(
                RuntimeEnvironment.getApplication(),
                StartExUiEvent.Authenticate(AuthenticationPurpose.UnlockTrustedAddress),
            )

        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            prompt.allowedAuthenticators,
        )
    }
}
