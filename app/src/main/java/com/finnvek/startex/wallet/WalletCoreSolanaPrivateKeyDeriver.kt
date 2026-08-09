package com.finnvek.startex.wallet

import wallet.core.jni.CoinType
import wallet.core.jni.HDWallet

internal fun interface SolanaPrivateKeyDeriver {
    fun derive(entropy: ByteArray): ByteArray
}

internal object WalletCoreSolanaPrivateKeyDeriver : SolanaPrivateKeyDeriver {
    override fun derive(entropy: ByteArray): ByteArray {
        TrustWalletCoreNative.ensureLoaded()
        return HDWallet(entropy, "")
            .getKey(CoinType.SOLANA, SolanaWalletDerivationPath.VALUE)
            .data()
    }
}

private object TrustWalletCoreNative {
    init {
        System.loadLibrary("TrustWalletCore")
    }

    fun ensureLoaded() = Unit
}
