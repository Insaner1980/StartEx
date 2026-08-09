package com.finnvek.startex.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun `defaults are paper mode and require an authenticated session`() {
        val settings = AppSettings()

        assertEquals(OperatingMode.PAPER, settings.operatingMode)
        assertTrue(settings.secureSession)
        assertFalse(settings.unattendedMode)
        assertFalse(settings.demoMode)
    }

    @Test
    fun `cleanup settings reject unsafe bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppSettings(snapshotRetentionDays = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppSettings(maximumStoredEvents = 100_001)
        }
    }

    @Test
    fun `exactly one wallet security mode must be active`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppSettings(secureSession = true, unattendedMode = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppSettings(secureSession = false, unattendedMode = false)
        }
    }
}
