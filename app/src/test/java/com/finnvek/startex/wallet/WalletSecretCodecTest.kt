package com.finnvek.startex.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletSecretCodecTest {
    @Test
    fun `mnemonic round trip consumes both mutable input buffers`() {
        val expected =
            "abandon ability able about above absent absorb abstract absurd abuse access accident"
                .toCharArray()
        val mnemonic = expected.copyOf()

        val encoded = WalletSecretCodec.encodeAndClear(mnemonic)
        assertTrue(mnemonic.all { it == '0' })

        val decoded = WalletSecretCodec.decodeAndClear(encoded)
        assertTrue(encoded.all { it == 0.toByte() })
        assertArrayEquals(expected, decoded)

        expected.fill('0')
        decoded.fill('0')
    }
}
