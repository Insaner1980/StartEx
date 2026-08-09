package com.finnvek.startex.wallet

import cash.z.ecc.android.bip39.Mnemonics.MnemonicCode
import cash.z.ecc.android.bip39.Mnemonics.WordCount
import com.finnvek.startex.security.clearSecret
import org.sol4k.Keypair
import java.io.Closeable

class LocalWalletFactory internal constructor(
    private val privateKeyDeriver: SolanaPrivateKeyDeriver = WalletCoreSolanaPrivateKeyDeriver,
) {
    fun create(): NewLocalWallet {
        MnemonicCode(WordCount.COUNT_24).use { mnemonic ->
            val phrase = mnemonic.chars.copyOf()
            return try {
                NewLocalWallet(phrase, deriveWallet(mnemonic))
            } finally {
                phrase.fill('0')
            }
        }
    }

    fun restore(phrase: CharArray): LocalWallet {
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
