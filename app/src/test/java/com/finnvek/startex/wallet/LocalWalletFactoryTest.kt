package com.finnvek.startex.wallet

import cash.z.ecc.android.bip39.Mnemonics.InvalidWordException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.PublicKey

class LocalWalletFactoryTest {
    private val deriver = RecordingPrivateKeyDeriver()
    private val factory = LocalWalletFactory(deriver)

    @Test
    fun `restore validates the mnemonic and derives from its entropy`() {
        val phrase = VALID_MNEMONIC.toCharArray()

        factory.restore(phrase).use { wallet ->
            assertEquals(deriver.expectedAddress, wallet.publicAddress)
        }

        assertArrayEquals(ByteArray(16) { 0x7f }, deriver.receivedEntropy)
        assertTrue(deriver.passedEntropy.all { it == 0.toByte() })
    }

    @Test
    fun `restored wallet signs with its derived public key`() {
        val phrase = validMnemonic()
        val message = "StartEx wallet verification".encodeToByteArray()

        factory.restore(phrase).use { wallet ->
            val signature = wallet.sign(message)

            assertTrue(PublicKey(wallet.publicAddress).verify(signature, message))
        }
    }

    @Test
    fun `creates a fresh 24 word wallet backup`() {
        factory.create().use { created ->
            val phrase = created.mnemonic

            assertEquals(24, phrase.count { it == ' ' } + 1)
            assertEquals(created.wallet.publicAddress, SolanaAddressValidator().normalize(created.wallet.publicAddress))
            phrase.fill('0')
        }

        assertEquals(32, deriver.receivedEntropy.size)
        assertTrue(deriver.passedEntropy.all { it == 0.toByte() })
    }

    @Test(expected = InvalidWordException::class)
    fun `restore rejects a phrase outside the BIP39 word list`() {
        val phrase =
            validMnemonic().also { chars ->
                "xxxxx".toCharArray().copyInto(chars, destinationOffset = 0)
            }

        factory.restore(phrase)
    }

    @Test(expected = IllegalStateException::class)
    fun `closed wallet cannot sign`() {
        val phrase = validMnemonic()
        val wallet = factory.restore(phrase)
        wallet.close()

        wallet.sign(byteArrayOf(1))
    }

    private fun validMnemonic(): CharArray = VALID_MNEMONIC.toCharArray()

    private class RecordingPrivateKeyDeriver : SolanaPrivateKeyDeriver {
        private val privateKey = ByteArray(32) { index -> (index + 1).toByte() }
        val expectedAddress =
            org.sol4k.Keypair
                .fromSecretKey(privateKey)
                .publicKey
                .toBase58()
        lateinit var receivedEntropy: ByteArray
        lateinit var passedEntropy: ByteArray

        override fun derive(entropy: ByteArray): ByteArray {
            receivedEntropy = entropy.copyOf()
            passedEntropy = entropy
            return privateKey.copyOf()
        }
    }

    private companion object {
        const val VALID_MNEMONIC =
            "legal winner thank year wave sausage worth useful legal winner thank yellow"
    }
}
