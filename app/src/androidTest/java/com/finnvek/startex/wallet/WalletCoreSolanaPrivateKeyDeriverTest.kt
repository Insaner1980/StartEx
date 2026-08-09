package com.finnvek.startex.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class WalletCoreSolanaPrivateKeyDeriverTest {
    @Test
    fun derivesKnownSolanaAccountFromBip39Entropy() {
        val phrase = VALID_MNEMONIC.toCharArray()

        try {
            LocalWalletFactory().restore(phrase).use { wallet ->
                assertEquals(EXPECTED_ADDRESS, wallet.publicAddress)
            }
        } finally {
            phrase.fill('0')
        }
    }

    private companion object {
        const val VALID_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
        const val EXPECTED_ADDRESS = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
    }
}
