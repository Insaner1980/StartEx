package com.finnvek.startex.ui

import com.finnvek.startex.R
import com.finnvek.startex.security.SecretEnvelopeFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class SecretEnvelopeFailurePresentationTest {
    @Test
    fun `wallet failures expose recovery guidance instead of cryptographic details`() {
        val cases =
            mapOf(
                SecretEnvelopeFailure.AUTHENTICATION_TAG_INVALID to R.string.wallet_data_unreadable,
                SecretEnvelopeFailure.KEY_MISSING to R.string.wallet_key_unavailable,
                SecretEnvelopeFailure.KEY_INVALIDATED to R.string.wallet_key_unavailable,
                SecretEnvelopeFailure.AUTHENTICATION_REQUIRED to R.string.wallet_authentication_retry,
                SecretEnvelopeFailure.CORRUPTED_ROW to R.string.wallet_data_unreadable,
                SecretEnvelopeFailure.UNSUPPORTED_VERSION to R.string.wallet_version_unsupported,
                SecretEnvelopeFailure.DATABASE_IO to R.string.wallet_storage_unavailable,
                SecretEnvelopeFailure.UNKNOWN to R.string.wallet_unlock_failed,
            )

        cases.forEach { (failure, expected) ->
            assertEquals(expected, walletEnvelopeFailureMessage(failure))
        }
    }

    @Test
    fun `provider failures expose retry or replacement guidance`() {
        val cases =
            mapOf(
                SecretEnvelopeFailure.AUTHENTICATION_TAG_INVALID to R.string.provider_key_reenter_required,
                SecretEnvelopeFailure.KEY_MISSING to R.string.provider_key_reenter_required,
                SecretEnvelopeFailure.KEY_INVALIDATED to R.string.provider_key_reenter_required,
                SecretEnvelopeFailure.AUTHENTICATION_REQUIRED to R.string.provider_key_retry,
                SecretEnvelopeFailure.CORRUPTED_ROW to R.string.provider_key_reenter_required,
                SecretEnvelopeFailure.UNSUPPORTED_VERSION to R.string.provider_key_version_unsupported,
                SecretEnvelopeFailure.DATABASE_IO to R.string.provider_key_storage_unavailable,
                SecretEnvelopeFailure.UNKNOWN to R.string.provider_key_restore_failed,
            )

        cases.forEach { (failure, expected) ->
            assertEquals(expected, providerEnvelopeFailureMessage(failure))
        }
    }
}
