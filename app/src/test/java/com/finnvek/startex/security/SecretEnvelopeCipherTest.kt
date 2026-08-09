package com.finnvek.startex.security

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecretEnvelopeCipherTest {
    private val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")

    @Test
    fun `AES GCM envelope round trips secret bytes`() {
        val secret = ByteArray(32) { it.toByte() }
        val encryptionCipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
        val envelope =
            SecretEnvelopeCipher.encrypt(
                cipher = encryptionCipher,
                secret = secret,
                publicAddress = "11111111111111111111111111111111",
            )
        val decryptionCipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv))
            }

        val restored = SecretEnvelopeCipher.decrypt(decryptionCipher, envelope)

        assertArrayEquals(secret, restored)
        restored.clearSecret()
        secret.clearSecret()
    }

    @Test(expected = AEADBadTagException::class)
    fun `public address metadata is authenticated`() {
        val encryptionCipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
        val envelope =
            SecretEnvelopeCipher.encrypt(
                cipher = encryptionCipher,
                secret = ByteArray(32) { 7 },
                publicAddress = "11111111111111111111111111111111",
            )
        val tampered =
            SecretEnvelope(
                version = envelope.version,
                publicAddress = "Vote111111111111111111111111111111111111111",
                iv = envelope.iv,
                ciphertext = envelope.ciphertext,
            )
        val decryptionCipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv))
            }

        SecretEnvelopeCipher.decrypt(decryptionCipher, tampered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported envelope version fails closed`() {
        val envelope =
            SecretEnvelope(
                version = 2,
                publicAddress = "11111111111111111111111111111111",
                iv = ByteArray(12),
                ciphertext = ByteArray(48),
            )
        val decryptionCipher = Cipher.getInstance("AES/GCM/NoPadding")

        SecretEnvelopeCipher.decrypt(decryptionCipher, envelope)
    }
}
