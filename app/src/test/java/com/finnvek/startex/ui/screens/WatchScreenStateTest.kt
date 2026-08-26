package com.finnvek.startex.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchScreenStateTest {
    @Test
    fun `blank candidate metadata falls back without hiding a valid name`() {
        assertEquals("Token name", candidateDisplayName("", "Token name", "Unknown token"))
        assertEquals("Unknown token", candidateDisplayName("   ", "", "Unknown token"))
    }
}
