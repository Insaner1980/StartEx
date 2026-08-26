package com.finnvek.startex.wallet

import cash.z.ecc.android.bip39.Mnemonics.ChecksumException
import cash.z.ecc.android.bip39.Mnemonics.InvalidWordException
import cash.z.ecc.android.bip39.Mnemonics.MnemonicCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.PublicKey
import java.security.SecureRandom

class LocalWalletFactoryTest {
    private val deriver = RecordingPrivateKeyDeriver()
    private val secureRandom = RecordingSecureRandom()
    private val factory = LocalWalletFactory(deriver, secureRandom)

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
    fun `creates a valid 24 word wallet backup and clears generated entropy`() {
        factory.create().use { created ->
            val phrase = created.mnemonic
            val validationPhrase = phrase.copyOf()

            assertEquals(24, phrase.count { it == ' ' } + 1)
            MnemonicCode(validationPhrase).use { mnemonic ->
                assertEquals(24, mnemonic.wordCount)
                mnemonic.validate()
            }
            assertEquals(created.wallet.publicAddress, SolanaAddressValidator().normalize(created.wallet.publicAddress))
            phrase.fill('0')
        }

        assertArrayEquals(secureRandom.expectedEntropy, deriver.receivedEntropy)
        assertTrue(secureRandom.passedEntropy.all { it == 0.toByte() })
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

    @Test(expected = ChecksumException::class)
    fun `restore rejects an invalid BIP39 checksum`() {
        val phrase =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"
                .toCharArray()

        try {
            factory.restore(phrase)
        } finally {
            phrase.fill('0')
        }
    }

    @Test
    fun `invalid mnemonic forms fail before private key derivation`() {
        val invalidPhrases =
            mapOf(
                "wrong word count" to VALID_MNEMONIC.substringBeforeLast(' '),
                "unknown word" to VALID_MNEMONIC.replaceFirst("legal", "xxxxx"),
                "checksum failure" to
                    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon",
                "Unicode lookalike" to VALID_MNEMONIC.replaceFirst("legal", "l\u0435gal"),
                "duplicate whitespace" to VALID_MNEMONIC.replaceFirst(" ", "  "),
                "newline paste" to VALID_MNEMONIC.replaceFirst(" ", "\n"),
                "extremely long input" to "abandon ".repeat(MAX_MNEMONIC_INPUT_CHAR_COUNT + 1),
            )

        invalidPhrases.forEach { (case, value) ->
            val caseDeriver = RecordingPrivateKeyDeriver()
            val phrase = value.toCharArray()
            val result = runCatching { LocalWalletFactory(caseDeriver).restore(phrase).close() }

            assertTrue("$case should fail", result.isFailure)
            assertEquals("$case must not derive a private key", 0, caseDeriver.callCount)
            phrase.fill('0')
        }
    }

    @Test
    fun `derivation exception clears validated entropy`() {
        lateinit var passedEntropy: ByteArray
        val factory =
            LocalWalletFactory(
                SolanaPrivateKeyDeriver { entropy ->
                    passedEntropy = entropy
                    error("Library derivation failed")
                },
            )
        val phrase = validMnemonic()

        val result = runCatching { factory.restore(phrase).close() }

        assertEquals("Library derivation failed", result.exceptionOrNull()?.message)
        assertTrue(passedEntropy.all { it == 0.toByte() })
        phrase.fill('0')
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
        var callCount = 0
            private set

        override fun derive(entropy: ByteArray): ByteArray {
            callCount += 1
            receivedEntropy = entropy.copyOf()
            passedEntropy = entropy
            return privateKey.copyOf()
        }
    }

    private class RecordingSecureRandom : SecureRandom() {
        val expectedEntropy = ByteArray(32) { index -> (index + 1).toByte() }
        lateinit var passedEntropy: ByteArray

        override fun nextBytes(bytes: ByteArray) {
            expectedEntropy.copyInto(bytes)
            passedEntropy = bytes
        }
    }

    private companion object {
        const val VALID_MNEMONIC =
            "legal winner thank year wave sausage worth useful legal winner thank yellow"
    }
}
