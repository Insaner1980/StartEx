package com.finnvek.startex.security

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher

internal object SecretEnvelopeCipher {
    fun encrypt(
        cipher: Cipher,
        secret: ByteArray,
        publicAddress: String,
    ): SecretEnvelope {
        cipher.updateAAD(authenticatedMetadata(ENVELOPE_VERSION, publicAddress))
        val ciphertext = cipher.doFinal(secret)
        return try {
            SecretEnvelope(
                version = ENVELOPE_VERSION,
                publicAddress = publicAddress,
                iv = cipher.iv,
                ciphertext = ciphertext,
            )
        } finally {
            ciphertext.clearSecret()
        }
    }

    fun decrypt(
        cipher: Cipher,
        envelope: SecretEnvelope,
    ): ByteArray {
        require(envelope.version == ENVELOPE_VERSION) { "Unsupported secret envelope version" }
        cipher.updateAAD(authenticatedMetadata(envelope.version, envelope.publicAddress))
        val ciphertext = envelope.ciphertext
        return try {
            cipher.doFinal(ciphertext)
        } finally {
            ciphertext.clearSecret()
        }
    }

    private fun authenticatedMetadata(
        version: Int,
        publicAddress: String,
    ): ByteArray {
        val address = publicAddress.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer
            .allocate(Int.SIZE_BYTES + address.size)
            .putInt(version)
            .put(address)
            .array()
    }

    private const val ENVELOPE_VERSION = 1
}
