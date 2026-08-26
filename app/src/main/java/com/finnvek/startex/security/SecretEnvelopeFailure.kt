package com.finnvek.startex.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

internal enum class SecretEnvelopeFailure {
    AUTHENTICATION_TAG_INVALID,
    KEY_MISSING,
    KEY_INVALIDATED,
    AUTHENTICATION_REQUIRED,
    CORRUPTED_ROW,
    UNSUPPORTED_VERSION,
    DATABASE_IO,
    UNKNOWN,
}

internal class MissingSecretEnvelopeKeyException : IllegalStateException("Secret envelope key is unavailable")

internal class CorruptedSecretEnvelopeException(
    cause: Throwable? = null,
) : IllegalArgumentException("Stored secret envelope is invalid", cause)

internal class UnsupportedSecretEnvelopeVersionException : IllegalArgumentException("Secret envelope version is unsupported")

internal class SecretEnvelopeDatabaseException(
    cause: Throwable,
) : IllegalStateException("Secret envelope storage is unavailable", cause)

internal fun classifySecretEnvelopeFailure(error: Throwable): SecretEnvelopeFailure {
    generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).forEach { cause ->
        when (cause) {
            is SecretEnvelopeDatabaseException -> return SecretEnvelopeFailure.DATABASE_IO

            is UnsupportedSecretEnvelopeVersionException -> return SecretEnvelopeFailure.UNSUPPORTED_VERSION

            is CorruptedSecretEnvelopeException -> return SecretEnvelopeFailure.CORRUPTED_ROW

            is MissingSecretEnvelopeKeyException -> return SecretEnvelopeFailure.KEY_MISSING

            is KeyPermanentlyInvalidatedException,
            is UnrecoverableKeyException,
            -> return SecretEnvelopeFailure.KEY_INVALIDATED

            is UserNotAuthenticatedException -> return SecretEnvelopeFailure.AUTHENTICATION_REQUIRED

            is AEADBadTagException -> return SecretEnvelopeFailure.AUTHENTICATION_TAG_INVALID
        }
    }
    return SecretEnvelopeFailure.UNKNOWN
}

private const val MAX_CAUSE_DEPTH = 16
