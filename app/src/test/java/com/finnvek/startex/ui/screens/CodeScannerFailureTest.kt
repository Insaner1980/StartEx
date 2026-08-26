package com.finnvek.startex.ui.screens

import com.google.mlkit.common.MlKitException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeScannerFailureTest {
    @Test
    fun `user cancellation is not reported as scanner failure`() {
        assertTrue(isCodeScannerCancellation(MlKitException.CODE_SCANNER_CANCELLED))
    }

    @Test
    fun `scanner unavailability remains a visible failure`() {
        assertFalse(isCodeScannerCancellation(MlKitException.CODE_SCANNER_UNAVAILABLE))
    }

    @Test
    fun `scanner accepts only a canonical Solana address`() {
        val address = "11111111111111111111111111111111"

        assertEquals(address, "solana:$address?amount=1".toTrustedAddressValue())
        assertNull("https://example.com".toTrustedAddressValue())
        assertNull("not-a-solana-address".toTrustedAddressValue())
    }
}
