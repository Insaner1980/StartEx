package com.finnvek.startex.security

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

class SecretEnvelopeFailureTest {
    @Test
    fun `classifies envelope failures by safe recovery action`() {
        val cases =
            listOf(
                AEADBadTagException() to SecretEnvelopeFailure.AUTHENTICATION_TAG_INVALID,
                MissingSecretEnvelopeKeyException() to SecretEnvelopeFailure.KEY_MISSING,
                UnrecoverableKeyException() to SecretEnvelopeFailure.KEY_INVALIDATED,
                CorruptedSecretEnvelopeException() to SecretEnvelopeFailure.CORRUPTED_ROW,
                UnsupportedSecretEnvelopeVersionException() to SecretEnvelopeFailure.UNSUPPORTED_VERSION,
                SecretEnvelopeDatabaseException(IOException()) to SecretEnvelopeFailure.DATABASE_IO,
                IllegalStateException("unknown") to SecretEnvelopeFailure.UNKNOWN,
            )

        cases.forEach { (error, expected) ->
            assertEquals(expected, classifySecretEnvelopeFailure(error))
        }
    }

    @Test
    fun `classifies wrapped keystore failures without relying on provider messages`() {
        val wrapped = IllegalStateException("operation failed", MissingSecretEnvelopeKeyException())

        assertEquals(
            SecretEnvelopeFailure.KEY_MISSING,
            classifySecretEnvelopeFailure(wrapped),
        )
    }
}
