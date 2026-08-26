package com.finnvek.startex.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecretEnvelopeAndroidFailureTest {
    @Test
    fun `classifies Android Keystore failures by safe recovery action`() {
        val cases =
            listOf(
                KeyPermanentlyInvalidatedException() to SecretEnvelopeFailure.KEY_INVALIDATED,
                UserNotAuthenticatedException() to SecretEnvelopeFailure.AUTHENTICATION_REQUIRED,
            )

        cases.forEach { (error, expected) ->
            assertEquals(expected, classifySecretEnvelopeFailure(error))
        }
    }
}
