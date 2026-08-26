package com.finnvek.startex.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightServiceStartTest {
    @Test
    fun `platform rejection keeps the start path recoverable`() {
        assertFalse(startMonitoringService { throw IllegalStateException("background start rejected") })
        assertFalse(startMonitoringService { throw SecurityException("permission rejected") })
    }

    @Test
    fun `accepted service start reports success`() {
        assertTrue(startMonitoringService {})
    }
}
