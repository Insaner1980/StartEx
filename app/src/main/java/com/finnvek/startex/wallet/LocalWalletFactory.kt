package com.finnvek.startex.wallet

import cash.z.ecc.android.bip39.Mnemonics.MnemonicCode
import com.finnvek.startex.security.clearSecret
import org.sol4k.Keypair
import java.io.Closeable
import java.security.SecureRandom

internal const val MAX_MNEMONIC_INPUT_CHAR_COUNT = 256

class LocalWalletFactory internal constructor(
    private val privateKeyDeriver: SolanaPrivateKeyDeriver = WalletCoreSolanaPrivateKeyDeriver,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun create(): NewLocalWallet {
        val entropy = ByteArray(BIP39_24_WORD_ENTROPY_SIZE)
        return try {
            secureRandom.nextBytes(entropy)
            MnemonicCode(entropy).use { mnemonic ->
                val phrase = mnemonic.chars.copyOf()
                try {
                    NewLocalWallet(phrase, deriveWallet(mnemonic))
                } finally {
                    phrase.fill('0')
                }
            }
        } finally {
            entropy.clearSecret()
        }
    }

    fun restore(phrase: CharArray): LocalWallet {
        require(phrase.size <= MAX_MNEMONIC_INPUT_CHAR_COUNT) { "Recovery phrase is too long" }
        val phraseCopy = phrase.copyOf()
        return try {
            MnemonicCode(phraseCopy).use { mnemonic ->
                mnemonic.validate()
                deriveWallet(mnemonic)
            }
        } finally {
            phraseCopy.fill('0')
        }
    }

    private fun deriveWallet(mnemonic: MnemonicCode): LocalWallet {
        val entropy = mnemonic.toEntropy()
        return try {
            val privateSeed = privateKeyDeriver.derive(entropy)
            try {
                LocalWallet(privateSeed)
            } finally {
                privateSeed.clearSecret()
            }
        } finally {
            entropy.clearSecret()
        }
    }

    private companion object {
        const val BIP39_24_WORD_ENTROPY_SIZE = 32
    }
}

object SolanaWalletDerivationPath {
    const val VALUE = "m/44'/501'/0'/0'"
}

class NewLocalWallet internal constructor(
    mnemonic: CharArray,
    val wallet: LocalWallet,
) : Closeable {
    private val mnemonicChars = mnemonic.copyOf()
    private var closed = false

    val mnemonic: CharArray
        get() {
            check(!closed) { "Wallet backup has been cleared" }
            return mnemonicChars.copyOf()
        }

    override fun close() {
        mnemonicChars.fill('0')
        wallet.close()
        closed = true
    }
}

class LocalWallet internal constructor(
    privateSeed: ByteArray,
) : Closeable,
    SolTransferSigner {
    init {
        require(privateSeed.size == PRIVATE_SEED_SIZE) { "Ed25519 private seed must contain 32 bytes" }
    }

    private val privateSeedBytes = privateSeed.copyOf()
    private var closed = false

    override val publicAddress: String = withKeypair { it.publicKey.toBase58() }

    @Synchronized
    override fun sign(message: ByteArray): ByteArray {
        check(!closed) { "Wallet has been cleared" }
        return withKeypair { it.sign(message) }
    }

    @Synchronized
    override fun close() {
        privateSeedBytes.clearSecret()
        closed = true
    }

    private fun <T> withKeypair(block: (Keypair) -> T): T {
        check(!closed) { "Wallet has been cleared" }
        val seedCopy = privateSeedBytes.copyOf()
        val keypair =
            try {
                Keypair.fromSecretKey(seedCopy)
            } finally {
                seedCopy.clearSecret()
            }
        return try {
            block(keypair)
        } finally {
            keypair.secret.clearSecret()
        }
    }

    private companion object {
        const val PRIVATE_SEED_SIZE = 32
    }
}
