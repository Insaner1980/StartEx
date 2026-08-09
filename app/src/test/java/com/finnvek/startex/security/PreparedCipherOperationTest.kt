package com.finnvek.startex.security

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import javax.crypto.Cipher

class PreparedCipherOperationTest {
    @Test
    fun `authenticated crypto object binds the operation to the returned cipher`() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val operation = PreparedCipherOperation(cipher, CipherPurpose.ENCRYPT)

        val authenticated = operation.bindAuthenticated(BiometricPrompt.CryptoObject(cipher))

        assertSame(cipher, authenticated?.cipher)
    }

    @Test
    fun `different crypto object cannot authorize the prepared operation`() {
        val operation =
            PreparedCipherOperation(
                Cipher.getInstance("AES/GCM/NoPadding"),
                CipherPurpose.DECRYPT,
            )
        val differentCipher = Cipher.getInstance("AES/GCM/NoPadding")

        assertNull(operation.bindAuthenticated(BiometricPrompt.CryptoObject(differentCipher)))
    }
}
