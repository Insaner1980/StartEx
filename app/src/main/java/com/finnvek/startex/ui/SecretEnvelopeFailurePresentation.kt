package com.finnvek.startex.ui

import androidx.annotation.StringRes
import com.finnvek.startex.R
import com.finnvek.startex.security.SecretEnvelopeFailure

@StringRes
internal fun walletEnvelopeFailureMessage(failure: SecretEnvelopeFailure): Int =
    when (failure) {
        SecretEnvelopeFailure.AUTHENTICATION_TAG_INVALID,
        SecretEnvelopeFailure.CORRUPTED_ROW,
        -> R.string.wallet_data_unreadable

        SecretEnvelopeFailure.KEY_MISSING,
        SecretEnvelopeFailure.KEY_INVALIDATED,
        -> R.string.wallet_key_unavailable

        SecretEnvelopeFailure.AUTHENTICATION_REQUIRED -> R.string.wallet_authentication_retry

        SecretEnvelopeFailure.UNSUPPORTED_VERSION -> R.string.wallet_version_unsupported

        SecretEnvelopeFailure.DATABASE_IO -> R.string.wallet_storage_unavailable

        SecretEnvelopeFailure.UNKNOWN -> R.string.wallet_unlock_failed
    }

@StringRes
internal fun providerEnvelopeFailureMessage(failure: SecretEnvelopeFailure): Int =
    when (failure) {
        SecretEnvelopeFailure.AUTHENTICATION_TAG_INVALID,
        SecretEnvelopeFailure.KEY_MISSING,
        SecretEnvelopeFailure.KEY_INVALIDATED,
        SecretEnvelopeFailure.CORRUPTED_ROW,
        -> R.string.provider_key_reenter_required

        SecretEnvelopeFailure.AUTHENTICATION_REQUIRED -> R.string.provider_key_retry

        SecretEnvelopeFailure.UNSUPPORTED_VERSION -> R.string.provider_key_version_unsupported

        SecretEnvelopeFailure.DATABASE_IO -> R.string.provider_key_storage_unavailable

        SecretEnvelopeFailure.UNKNOWN -> R.string.provider_key_restore_failed
    }
