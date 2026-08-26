package com.finnvek.startex.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiveAddressTest {
    @Test
    fun onlyACanonicalSolanaAddressCanBeReceivedTo() {
        val address = "11111111111111111111111111111111"

        assertEquals(address, receiveAddressOrNull(address))
        assertNull(receiveAddressOrNull(null))
        assertNull(receiveAddressOrNull(""))
        assertNull(receiveAddressOrNull(" $address "))
        assertNull(receiveAddressOrNull("x".repeat(100_000)))
    }
}
