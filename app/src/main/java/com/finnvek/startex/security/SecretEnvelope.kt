package com.finnvek.startex.security

class SecretEnvelope(
    val version: Int,
    val publicAddress: String,
    iv: ByteArray,
    ciphertext: ByteArray,
) {
    private val ivBytes: ByteArray
    private val ciphertextBytes: ByteArray

    val iv: ByteArray
        get() = ivBytes.copyOf()

    val ciphertext: ByteArray
        get() = ciphertextBytes.copyOf()

    init {
        require(version > 0) { "Envelope version must be positive" }
        require(publicAddress.isNotBlank()) { "Public address is required" }
        require(publicAddress.length <= MAX_METADATA_CHAR_COUNT) { "Envelope metadata exceeds the limit" }
        require(iv.size == GCM_IV_SIZE) { "AES-GCM IV must contain 12 bytes" }
        require(ciphertext.size > GCM_TAG_SIZE) { "Ciphertext must contain encrypted data and a GCM tag" }
        require(ciphertext.size <= MAX_CIPHERTEXT_SIZE) { "Ciphertext exceeds the secret envelope limit" }
        ivBytes = iv.copyOf()
        ciphertextBytes = ciphertext.copyOf()
    }

    private companion object {
        const val GCM_IV_SIZE = 12
        const val GCM_TAG_SIZE = 16
        const val MAX_METADATA_CHAR_COUNT = 128
        const val MAX_CIPHERTEXT_SIZE = 64 * 1024 + GCM_TAG_SIZE
    }
}

fun ByteArray.clearSecret() {
    fill(0)
}
