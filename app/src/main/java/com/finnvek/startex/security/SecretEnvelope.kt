package com.finnvek.startex.security

class SecretEnvelope(
    val version: Int,
    val publicAddress: String,
    iv: ByteArray,
    ciphertext: ByteArray,
) {
    private val ivBytes = iv.copyOf()
    private val ciphertextBytes = ciphertext.copyOf()

    val iv: ByteArray
        get() = ivBytes.copyOf()

    val ciphertext: ByteArray
        get() = ciphertextBytes.copyOf()

    init {
        require(version > 0) { "Envelope version must be positive" }
        require(publicAddress.isNotBlank()) { "Public address is required" }
        require(iv.size == GCM_IV_SIZE) { "AES-GCM IV must contain 12 bytes" }
        require(ciphertext.size > GCM_TAG_SIZE) { "Ciphertext must contain encrypted data and a GCM tag" }
    }

    private companion object {
        const val GCM_IV_SIZE = 12
        const val GCM_TAG_SIZE = 16
    }
}

fun ByteArray.clearSecret() {
    fill(0)
}
