package com.finnvek.startex.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SolanaAddressValidatorTest {
    private val validator = SolanaAddressValidator()

    @Test
    fun `normalizes a valid base58 public key`() {
        assertEquals(
            "11111111111111111111111111111111",
            validator.normalize("11111111111111111111111111111111"),
        )
    }

    @Test
    fun `rejects malformed and wrong length public keys`() {
        assertNull(validator.normalize("not-a-solana-address"))
        assertNull(validator.normalize("111111111111111111111111111111111"))
    }
}
