package com.finnvek.startex

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Test

class BiometricErrorPresentationTest {
    @Test
    fun `lockout directs the user to unlock with device credentials`() {
        assertEquals(
            R.string.authentication_locked_out,
            biometricAuthenticationErrorMessage(BiometricPrompt.ERROR_LOCKOUT),
        )
        assertEquals(
            R.string.authentication_locked_out,
            biometricAuthenticationErrorMessage(BiometricPrompt.ERROR_LOCKOUT_PERMANENT),
        )
    }

    @Test
    fun `device lock compatible cancellation remains a retryable authentication failure`() {
        assertEquals(
            R.string.authentication_temporarily_unavailable,
            biometricAuthenticationErrorMessage(BiometricPrompt.ERROR_CANCELED),
        )
        assertEquals(
            R.string.authentication_temporarily_unavailable,
            biometricAuthenticationErrorMessage(BiometricPrompt.ERROR_HW_UNAVAILABLE),
        )
        assertEquals(
            R.string.authentication_temporarily_unavailable,
            biometricAuthenticationErrorMessage(BiometricPrompt.ERROR_TIMEOUT),
        )
    }

    @Test
    fun `user cancellation keeps the non destructive generic message`() {
        assertEquals(
            R.string.authentication_failed,
            biometricAuthenticationErrorMessage(BiometricPrompt.ERROR_USER_CANCELED),
        )
    }
}
