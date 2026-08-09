package com.finnvek.startex.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class KeystoreAccessMode {
    BIOMETRIC_EACH_USE,
    UNATTENDED,
}

class PreparedCipherOperation internal constructor(
    internal val cipher: Cipher,
    internal val purpose: CipherPurpose,
) {
    val cryptoObject = BiometricPrompt.CryptoObject(cipher)

    internal fun bindAuthenticated(authenticatedCryptoObject: BiometricPrompt.CryptoObject): PreparedCipherOperation? {
        val authenticatedCipher = authenticatedCryptoObject.cipher ?: return null
        if (authenticatedCipher !== cipher) return null
        return PreparedCipherOperation(authenticatedCipher, purpose)
    }

    companion object {
        const val ALLOWED_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
}

internal enum class CipherPurpose {
    ENCRYPT,
    DECRYPT,
}

class AndroidKeystoreSecretCipher private constructor(
    private val keyAlias: String,
    val accessMode: KeystoreAccessMode,
) {
    init {
        require(keyAlias.isNotBlank()) { "Keystore alias is required" }
    }

    val requiresBiometricAuthentication: Boolean
        get() = accessMode == KeystoreAccessMode.BIOMETRIC_EACH_USE

    fun prepareEncryption(): PreparedCipherOperation {
        val cipher =
            Cipher.getInstance(AES_GCM_NO_PADDING).apply {
                init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            }
        return PreparedCipherOperation(cipher, CipherPurpose.ENCRYPT)
    }

    fun prepareDecryption(envelope: SecretEnvelope): PreparedCipherOperation {
        val cipher =
            Cipher.getInstance(AES_GCM_NO_PADDING).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    getExistingKey(),
                    GCMParameterSpec(GCM_TAG_SIZE_BITS, envelope.iv),
                )
            }
        return PreparedCipherOperation(cipher, CipherPurpose.DECRYPT)
    }

    fun encrypt(
        operation: PreparedCipherOperation,
        secret: ByteArray,
        publicAddress: String,
    ): SecretEnvelope {
        require(operation.purpose == CipherPurpose.ENCRYPT) { "Encryption operation required" }
        return SecretEnvelopeCipher.encrypt(operation.cipher, secret, publicAddress)
    }

    fun decrypt(
        operation: PreparedCipherOperation,
        envelope: SecretEnvelope,
    ): ByteArray {
        require(operation.purpose == CipherPurpose.DECRYPT) { "Decryption operation required" }
        return SecretEnvelopeCipher.decrypt(operation.cipher, envelope)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        return (keyStore.getKey(keyAlias, null) as? SecretKey) ?: generateKey()
    }

    private fun getExistingKey(): SecretKey =
        loadKeyStore().getKey(keyAlias, null) as? SecretKey
            ?: error("Wallet wrapping key is unavailable")

    private fun generateKey(): SecretKey {
        val builder =
            KeyGenParameterSpec
                .Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setKeySize(AES_KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)

        if (requiresBiometricAuthentication) {
            builder
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .setUnlockedDeviceRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        fun secureSession(): AndroidKeystoreSecretCipher =
            AndroidKeystoreSecretCipher(
                keyAlias = SECURE_SESSION_KEY_ALIAS,
                accessMode = KeystoreAccessMode.BIOMETRIC_EACH_USE,
            )

        fun unattended(): AndroidKeystoreSecretCipher =
            AndroidKeystoreSecretCipher(
                keyAlias = UNATTENDED_KEY_ALIAS,
                accessMode = KeystoreAccessMode.UNATTENDED,
            )

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_TAG_SIZE_BITS = 128
        private const val SECURE_SESSION_KEY_ALIAS = "startex_wallet_secure_v1"
        private const val UNATTENDED_KEY_ALIAS = "startex_wallet_unattended_v1"
    }
}
