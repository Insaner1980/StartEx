package com.finnvek.startex.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SecretEnvelopeTest {
    @Test
    fun `keeps ciphertext and iv isolated from caller mutations`() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(48) { (it + 1).toByte() }
        val envelope =
            SecretEnvelope(
                version = 1,
                publicAddress = "11111111111111111111111111111111",
                iv = iv,
                ciphertext = ciphertext,
            )

        iv.fill(99)
        ciphertext.fill(99)
        val returnedIv = envelope.iv
        val returnedCiphertext = envelope.ciphertext
        returnedIv.fill(88)
        returnedCiphertext.fill(88)

        assertArrayEquals(ByteArray(12) { it.toByte() }, envelope.iv)
        assertArrayEquals(ByteArray(48) { (it + 1).toByte() }, envelope.ciphertext)
        assertEquals(1, envelope.version)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non GCM iv length`() {
        SecretEnvelope(1, "address", ByteArray(16), ByteArray(48))
    }

    @Test
    fun `clears sensitive bytes in place`() {
        val secret = byteArrayOf(1, 2, 3, 4)

        secret.clearSecret()

        assertArrayEquals(ByteArray(4), secret)
        assertNotEquals(1, secret[0].toInt())
    }
}
